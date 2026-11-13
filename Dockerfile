# First stage : setup the system and environment
FROM alpine:3.22 AS base

ARG TARGETARCH

# Install OpenJDK 17 and runtime dependencies.
# Chisel 7 simulates with ChiselSim, which drives Verilator, so a C++ toolchain
# (g++, make, musl-dev) and Perl (Verilator is a Perl script) are runtime dependencies.
# Stable Alpine ships Verilator for x86-64 only, so both architectures take it from the
# edge/community repository; the @edge tag keeps every other package on 3.22. Later apk
# calls need --no-cache (or a fresh index) so that the @edge tag still resolves.
# On Arm64, firtool (an x86-64 executable, see the intermediate-builder stage) runs
# through qemu-user.
RUN \
    echo "@edge https://dl-cdn.alpinelinux.org/alpine/edge/community" >> /etc/apk/repositories && \
    apk add --no-cache \
        openjdk17-jre-headless \
        graphviz \
        python3 \
        py3-pip \
        bash \
        libstdc++ \
        verilator@edge \
        verilator-dev@edge \
        g++ \
        make \
        musl-dev \
        perl \
        && \
    if [ "$TARGETARCH" = "arm64" ]; then apk add --no-cache qemu-x86_64; fi && \
    apk add --no-cache --virtual .build-deps \
        gcc \
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
    apk del --no-cache .build-deps

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk
ENV PATH=$JAVA_HOME/bin:$PATH

RUN adduser -D -s /bin/bash bootcamp

ENV SCALA_VERSION=2.13.18
ENV ALMOND_VERSION=0.14.5
ENV COURSIER_VERSION=2.1.24
ENV CHISEL_VERSION=7.16.0
# Must be the firtool version that Chisel $CHISEL_VERSION was published against
ENV FIRTOOL_VERSION=1.160.0
ENV JUPYTER_CONFIG_DIR=/jupyter/config
ENV JUPYTER_DATA_DIR=/jupyter/data

# Only the Jupyter configuration is needed here; the notebooks are copied last (final stage)
# so that editing them does not invalidate the cached dependency downloads.
COPY source/custom.js source/jupyter_server_config.py source/overrides.json /chisel-bootcamp/source/
WORKDIR /chisel-bootcamp

RUN mkdir -p $JUPYTER_CONFIG_DIR/custom && \
    mkdir -p $JUPYTER_CONFIG_DIR/lab/user-settings/@jupyterlab/apputils-extension && \
    cp source/custom.js $JUPYTER_CONFIG_DIR/custom/ && \
    cp source/jupyter_server_config.py $JUPYTER_CONFIG_DIR/ && \
    cp source/overrides.json $JUPYTER_CONFIG_DIR/lab/user-settings/@jupyterlab/apputils-extension/notification.jupyterlab-settings

# Ammonite and the build stage use this preloaded cache
ENV COURSIER_CACHE=/coursier_cache

# Second stage - download Scala requirements and the Scala kernel
FROM base AS intermediate-builder

ARG TARGETARCH

# Download everything into the cache that the final image uses at runtime
RUN mkdir /coursier_cache

# The JVM launcher of coursier works on every architecture.
RUN \
    apk add --no-cache curl unzip && \
    curl -fL --retry 3 -o coursier \
        "https://github.com/coursier/coursier/releases/download/v${COURSIER_VERSION}/coursier" && \
    chmod +x coursier && \
    ./coursier \
        bootstrap \
        -r jitpack \
        sh.almond:scala-kernel_$SCALA_VERSION:$ALMOND_VERSION \
        --default=true \
        -o almond && \
    ./almond --install --global && \
    # Preload Chisel dependencies into the coursier cache, which Ammonite uses at runtime
    # (Ammonite also looks for the source jars, so fetch them too)
    ./coursier fetch --sources --default=true \
        --intransitive \
        org.chipsalliance:chisel-plugin_$SCALA_VERSION:$CHISEL_VERSION \
        && \
    ./coursier fetch --sources --default=true \
        org.chipsalliance:chisel_2.13:$CHISEL_VERSION \
        org.scalatest:scalatest_2.13:3.2.19 \
        && \
    rm -rf almond coursier

# Chisel 7 emits SystemVerilog by running CIRCT's `firtool`, a self-contained static x86-64
# executable (Maven Central ships it inside the llvm-firtool jar). Install it once here so
# the bootcamp works offline. There is no native Linux/Arm64 build of firtool: on Arm64 it
# runs through qemu-user, installed in the base stage (the rest of the toolchain is native).
RUN \
    mkdir -p /opt/firtool/bin && \
    curl -fL --retry 3 -o firtool.jar \
        "https://repo1.maven.org/maven2/org/chipsalliance/llvm-firtool/${FIRTOOL_VERSION}/llvm-firtool-${FIRTOOL_VERSION}-linux-x64.jar" && \
    unzip -p firtool.jar org.chipsalliance/llvm-firtool/linux-x64/bin/firtool > /opt/firtool/bin/firtool.x86_64 && \
    rm firtool.jar && \
    chmod +x /opt/firtool/bin/firtool.x86_64 && \
    if [ "$TARGETARCH" = "arm64" ]; then \
        printf '#!/bin/sh\nexec qemu-x86_64 /opt/firtool/bin/firtool.x86_64 "$@"\n' > /opt/firtool/bin/firtool && \
        chmod +x /opt/firtool/bin/firtool; \
    else \
        mv /opt/firtool/bin/firtool.x86_64 /opt/firtool/bin/firtool; \
    fi

# Last stage
FROM base AS final

# Tell Chisel where to find the preinstalled firtool instead of downloading one
ENV CHISEL_FIRTOOL_PATH=/opt/firtool/bin

# copy the Scala requirements and kernel into the image
COPY --from=intermediate-builder --chown=bootcamp:bootcamp /coursier_cache/ /coursier_cache/
COPY --from=intermediate-builder --chown=bootcamp:bootcamp /usr/local/share/jupyter/kernels/scala/ /usr/local/share/jupyter/kernels/scala/
COPY --from=intermediate-builder /opt/firtool/ /opt/firtool/

COPY --chown=bootcamp:bootcamp . /chisel-bootcamp/

# The directory itself is root-owned (created by WORKDIR); ChiselSim writes ./build and Jupyter
# writes checkpoints there.
RUN chown -R bootcamp:bootcamp /jupyter && chown bootcamp:bootcamp /chisel-bootcamp

USER bootcamp
WORKDIR /chisel-bootcamp

EXPOSE 8888
CMD ["jupyter", "lab", "--no-browser", "--ip", "0.0.0.0", "--port", "8888"]
