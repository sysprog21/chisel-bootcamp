# First stage : setup the system and environment
FROM alpine:3.22 AS base

# Install OpenJDK 11 and runtime dependencies
RUN \
    apk add --no-cache \
        openjdk11-jre-headless \
        graphviz \
        python3 \
        py3-pip \
        bash \
        libstdc++ \
        && \
    apk add --no-cache --virtual .build-deps \
        gcc \
        g++ \
        musl-dev \
        linux-headers \
        python3-dev \
        && \
    pip3 install --no-cache-dir --break-system-packages jupyter jupyterlab && \
    find /usr/lib/python3.12 -type d -name __pycache__ -exec rm -rf {} + 2>/dev/null || true && \
    find /usr/lib/python3.12 -type f -name '*.pyc' -delete && \
    find /usr/lib/python3.12 -type f -name '*.pyo' -delete && \
    find /usr/lib/python3.12/site-packages/babel/locale-data -mindepth 1 ! -name 'en_*' -a ! -name 'root.dat' -delete 2>/dev/null || true && \
    find /usr/lib/python3.12/site-packages -type d -name tests -exec rm -rf {} + 2>/dev/null || true && \
    find /usr/lib/python3.12/site-packages -type d -name testing -exec rm -rf {} + 2>/dev/null || true && \
    rm -rf /usr/lib/python3.12/site-packages/pip /usr/lib/python3.12/site-packages/setuptools && \
    apk del .build-deps

ENV JAVA_HOME=/usr/lib/jvm/java-11-openjdk
ENV PATH=$JAVA_HOME/bin:$PATH

RUN adduser -D -s /bin/bash bootcamp

ENV SCALA_VERSION=2.12.10
ENV ALMOND_VERSION=0.9.1
ENV COURSIER_VERSION=2.1.24
ENV COURSIER_CACHE=/coursier_cache
ENV JUPYTER_CONFIG_DIR=/jupyter/config
ENV JUPYTER_DATA_DIR=/jupyter/data

COPY . /chisel-bootcamp/
WORKDIR /chisel-bootcamp

RUN mkdir -p $JUPYTER_CONFIG_DIR/custom && \
    mkdir -p $JUPYTER_CONFIG_DIR/lab/user-settings/@jupyterlab/apputils-extension && \
    cp source/custom.js $JUPYTER_CONFIG_DIR/custom/ && \
    cp source/jupyter_server_config.py $JUPYTER_CONFIG_DIR/ && \
    cp source/overrides.json $JUPYTER_CONFIG_DIR/lab/user-settings/@jupyterlab/apputils-extension/notification.jupyterlab-settings

# Second stage - download Scala requirements and the Scala kernel
FROM base AS intermediate-builder

ARG TARGETARCH

RUN mkdir /coursier_cache

# Install glibc for ARM64 coursier binary (x86-64 uses static musl build)
RUN \
    if [ "$TARGETARCH" = "arm64" ]; then \
        apk add --no-cache wget gcompat libstdc++ && \
        wget -q -O /etc/apk/keys/sgerrand.rsa.pub https://alpine-pkgs.sgerrand.com/sgerrand.rsa.pub && \
        wget -q https://github.com/sgerrand/alpine-pkg-glibc/releases/download/2.35-r1/glibc-2.35-r1.apk && \
        apk --no-cache --force-overwrite add glibc-2.35-r1.apk && \
        rm glibc-2.35-r1.apk && \
        ln -s /lib/ld-musl-aarch64.so.1 /lib/ld-linux-aarch64.so.1 2>/dev/null || true; \
    fi

RUN \
    apk add --no-cache curl && \
    case "$TARGETARCH" in \
        amd64|"") \
            CS_URL="https://github.com/coursier/coursier/releases/download/v${COURSIER_VERSION}/cs-x86_64-pc-linux-static.gz" ;; \
        arm64) \
            CS_URL="https://github.com/VirtusLab/coursier-m1/releases/download/v${COURSIER_VERSION}/cs-aarch64-pc-linux.gz" ;; \
        *) \
            echo "Unsupported architecture: $TARGETARCH" && exit 1 ;; \
    esac && \
    curl -fL --retry 3 "$CS_URL" | gzip -d > coursier && \
    chmod +x coursier && \
    ./coursier \
        bootstrap \
        -r jitpack \
        sh.almond:scala-kernel_$SCALA_VERSION:$ALMOND_VERSION \
        --default=true \
        -o almond && \
    ./almond --install --global && \
    # Preload Chisel dependencies to avoid first-run delay before removing coursier
    ./coursier fetch \
        edu.berkeley.cs:chisel3_2.12:3.6.1 \
        edu.berkeley.cs:chisel-iotesters_2.12:2.5.6 \
        edu.berkeley.cs:chiseltest_2.12:0.6.2 \
        edu.berkeley.cs:dsptools_2.12:1.5.6 \
        edu.berkeley.cs:rocket-dsptools_2.12:1.2.0 \
        org.scalanlp:breeze_2.12:1.0 \
        org.scalatest:scalatest_2.12:3.2.2 \
        --cache /coursier_cache && \
    rm -rf almond coursier /root/.cache/coursier

# Last stage
FROM base AS final

# copy the Scala requirements and kernel into the image
COPY --from=intermediate-builder --chown=bootcamp:bootcamp /coursier_cache/ /coursier_cache/
COPY --from=intermediate-builder --chown=bootcamp:bootcamp /usr/local/share/jupyter/kernels/scala/ /usr/local/share/jupyter/kernels/scala/

RUN chown -R bootcamp:bootcamp /chisel-bootcamp /jupyter

USER bootcamp
WORKDIR /chisel-bootcamp

EXPOSE 8888
CMD jupyter lab --no-browser --ip 0.0.0.0 --port 8888
