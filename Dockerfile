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
ENV SBT_VERSION=1.13.1
# sha256 of sbt-$SBT_VERSION.tgz (from its GitHub release); update together with SBT_VERSION
ENV SBT_SHA256=4a20e36c1642b6d5be34ef641d2ed6c8c0d464e6145283730d5566716e4083be
ENV CHISEL_VERSION=7.16.0
# Must be the firtool version that Chisel $CHISEL_VERSION was published against (checked by
# tools/check-versions.py, which also makes you revisit what 2.8_memories.ipynb says about how
# this firtool builds a SyncReadMem).
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

# Ammonite, sbt and the build stage use this preloaded cache
ENV COURSIER_CACHE=/coursier_cache
ENV PATH=/opt/sbt/bin:$PATH

# Second stage - download Scala requirements and the Scala kernel
FROM base AS intermediate-builder

ARG TARGETARCH
ARG BUILDARCH

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

# sbt for the riscv/ project (Module 5). sbt runs on the JRE: the project has no Java sources.
# Its own files go to /opt/sbt-home (set in sbt's configuration file, so every user gets it).
RUN \
    curl -fL --retry 3 -o sbt.tgz "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" && \
    echo "$SBT_SHA256  sbt.tgz" | sha256sum -c - && \
    tar -xzf sbt.tgz -C /opt && \
    rm sbt.tgz /opt/sbt/bin/sbtn-* /opt/sbt/bin/sbt.bat && \
    printf '%s\n' -Dsbt.global.base=/opt/sbt-home/global -Dsbt.boot.directory=/opt/sbt-home/boot \
        -Dsbt.ivy.home=/opt/sbt-home/ivy -Dsbt.server.forcestart=false >> /opt/sbt/conf/sbtopts

# Preload what `sbt test` needs offline: the sbt runtime and the Scala 2.12 bridge that compiles
# build.sbt (/opt/sbt-home), and the project's dependencies (/coursier_cache). Only the build
# definition and two placeholder sources are used, so editing riscv/src does not redo this step.
COPY riscv/build.sbt /tmp/riscv/
COPY riscv/project/build.properties /tmp/riscv/project/
RUN \
    mkdir -p /tmp/riscv/src/main/scala /tmp/riscv/src/test/scala && \
    echo 'class Warmup extends chisel3.Module' > /tmp/riscv/src/main/scala/Warmup.scala && \
    echo 'class WarmupSpec extends org.scalatest.flatspec.AnyFlatSpec with chisel3.simulator.scalatest.ChiselSim' \
        > /tmp/riscv/src/test/scala/WarmupSpec.scala && \
    cd /tmp/riscv && \
    # Under qemu (an Arm64 image cross-built on x86-64) the JVM crashes while probing SVE
    if [ "$TARGETARCH" = "arm64" ] && [ "$BUILDARCH" != "arm64" ]; then export JAVA_TOOL_OPTIONS="-XX:UseSVE=0"; fi && \
    sbt -batch update Test/compile && \
    # (sbt regenerates java9-rt-ext, 59 MB, offline in a few seconds on first use)
    cd / && rm -rf /tmp/riscv /opt/sbt-home/global/server /opt/sbt-home/global/java9-rt-ext-*

# Last stage
FROM base AS final

# Tell Chisel where to find the preinstalled firtool instead of downloading one
ENV CHISEL_FIRTOOL_PATH=/opt/firtool/bin

# copy the Scala requirements and kernel into the image
COPY --from=intermediate-builder --chown=bootcamp:bootcamp /coursier_cache/ /coursier_cache/
COPY --from=intermediate-builder --chown=bootcamp:bootcamp /usr/local/share/jupyter/kernels/scala/ /usr/local/share/jupyter/kernels/scala/
COPY --from=intermediate-builder /opt/firtool/ /opt/firtool/
COPY --from=intermediate-builder /opt/sbt/ /opt/sbt/
COPY --from=intermediate-builder --chown=bootcamp:bootcamp /opt/sbt-home/ /opt/sbt-home/

COPY --chown=bootcamp:bootcamp . /chisel-bootcamp/

# The directory itself is root-owned (created by WORKDIR); ChiselSim writes ./build and Jupyter
# writes checkpoints there.
RUN chown -R bootcamp:bootcamp /jupyter && chown bootcamp:bootcamp /chisel-bootcamp

USER bootcamp
WORKDIR /chisel-bootcamp

EXPOSE 8888
CMD ["jupyter", "lab", "--no-browser", "--ip", "0.0.0.0", "--port", "8888"]
