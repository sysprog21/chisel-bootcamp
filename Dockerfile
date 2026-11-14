# A Java 17 runtime cut down to the modules the course uses (the Scala compiler, Almond, coursier
# and sbt): 92 MB instead of the 172 MB of Alpine's JRE, with a class-data sharing archive so that
# the JVM starts as fast. Its window-system, sound, font and image libraries go (nothing here
# draws, plays or reads images); its cacerts is replaced by the system's (java-cacerts, in the base stage).
FROM alpine:3.22 AS jre
ARG TARGETARCH
ARG BUILDARCH
RUN \
    apk add --no-cache openjdk17-jdk binutils && \
    # Under qemu (an Arm64 image cross-built on x86-64) the JVM crashes while probing SVE
    if [ "$TARGETARCH" = "arm64" ] && [ "$BUILDARCH" != "arm64" ]; then export JAVA_TOOL_OPTIONS="-XX:UseSVE=0"; fi && \
    jlink --add-modules java.se,jdk.unsupported,jdk.zipfs,jdk.crypto.ec,jdk.charsets,jdk.management \
        --strip-debug --no-header-files --no-man-pages --generate-cds-archive \
        --output /opt/java && \
    rm /opt/java/lib/libawt_xawt.so /opt/java/lib/libjsound.so /opt/java/lib/libsplashscreen.so \
        /opt/java/lib/libjawt.so /opt/java/lib/liblcms.so /opt/java/lib/libfontmanager.so \
        /opt/java/lib/libjavajpeg.so \
        /opt/java/lib/server/classes_nocoops.jsa # (for heaps over 32 GB)

# Then set up the system and environment
FROM alpine:3.22 AS base

ARG TARGETARCH

# Install the Java runtime above, and the runtime dependencies.
# Chisel 7 simulates with ChiselSim, which drives Verilator, so a C++ toolchain
# (g++, make, musl-dev) is a runtime dependency. Verilator's `verilator` command is a Perl wrapper
# around verilator_bin; a shell script below takes its place, so that the image has no Perl.
# Stable Alpine ships Verilator for x86-64 only, so both architectures take it from the
# edge/community repository; the @edge tag keeps every other package on 3.22. Later apk
# calls need --no-cache (or a fresh index) so that the @edge tag still resolves.
# On Arm64, firtool (an x86-64 executable, see the intermediate-builder stage) runs
# through qemu-user. ChiselSim needs Verilator 5.026 or newer (older ones abort now and then).
# Yosys (for the formal check of 5.10) is not packaged for stable Alpine; YoWASP's build is Yosys
# compiled to WebAssembly, run by wasmtime (wheels for musl on x86-64 and Arm64), and installed as
# `yosys`. It adds about 115 MB; its first run in a container compiles it (about a minute).
# The course needs only JupyterLab and the Scala kernel: the Python kernel that JupyterLab pulls in
# (ipykernel, IPython, jedi, debugpy) is uninstalled, about 80 MB. The image has no RISC-V
# toolchain (riscv.Asm assembles programs); the host GCC is there for Verilator, which compiles
# C++ only, so its C compiler, link-time optimizer, plugin headers, gcov and static C libraries go
# (about 140 MB), as do Verilator's debug binaries.
RUN \
    echo "@edge https://dl-cdn.alpinelinux.org/alpine/edge/community" >> /etc/apk/repositories && \
    apk add --no-cache \
        zlib \
        java-cacerts \
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
        && \
    if [ "$TARGETARCH" = "arm64" ]; then apk add --no-cache qemu-x86_64; fi && \
    apk add --no-cache --virtual .build-deps \
        gcc \
        linux-headers \
        python3-dev \
        && \
    pip3 install --no-cache-dir --break-system-packages jupyterlab \
        yowasp-yosys==0.71.0.0.post1285 yowasp-runtime==1.96 wasmtime==47.0.1 && \
    pip3 uninstall -y --break-system-packages ipykernel ipython ipython-pygments-lexers jedi parso \
        debugpy prompt-toolkit wcwidth stack-data executing asttokens pure-eval matplotlib-inline \
        comm nest-asyncio nest-asyncio2 psutil pexpect && \
    ln -s "$(command -v yowasp-yosys)" /usr/local/bin/yosys && \
    { find /usr/lib/python3.12 -type d -name __pycache__ -exec rm -rf {} + || true; } 2>/dev/null && \
    find /usr/lib/python3.12 -type f -name '*.pyc' -delete && \
    find /usr/lib/python3.12 -type f -name '*.pyo' -delete && \
    { find /usr/lib/python3.12/site-packages/babel/locale-data -mindepth 1 ! -name 'en_*' -a ! -name 'root.dat' -delete || true; } 2>/dev/null && \
    { find /usr/lib/python3.12/site-packages -type d -name tests -exec rm -rf {} + || true; } 2>/dev/null && \
    { find /usr/lib/python3.12/site-packages -type d -name testing -exec rm -rf {} + || true; } 2>/dev/null && \
    rm -rf /usr/lib/python3.12/site-packages/pip /usr/lib/python3.12/site-packages/setuptools \
        /usr/lib/python3.12/ensurepip /usr/lib/python3.12/lib2to3 /usr/lib/python3.12/pydoc_data \
        /usr/lib/python3.12/idlelib /usr/lib/python3.12/tkinter /usr/lib/python3.12/turtledemo \
        /usr/lib/python3.12/test /usr/lib/python3.12/site-packages/jupyterlab/galata && \
    find /usr/lib/python3.12/site-packages -name '*.map' -delete && \
    # Yosys's techmap libraries: 5.10 synthesizes for iCE40 and ECP5 only
    cd /usr/lib/python3.12/site-packages/yowasp_yosys/share && \
    for d in */; do case "$d" in ice40/|ecp5/|lattice/|include/|python3/|choices/|sdc/) ;; *) rm -r "$d" ;; esac; done && \
    cd / && \
    # (the server must still start without the packages uninstalled above)
    python3 -B -c 'import jupyter_server.serverapp, jupyterlab.labapp' && \
    apk del --no-cache .build-deps && \
    # (gcc prints a bare name for what it cannot find: check both paths before deleting)
    cc1plus=$(gcc -print-prog-name=cc1plus) && plugin=$(gcc -print-file-name=plugin) && \
    test -x "$cc1plus" && test -d "$plugin" && \
    rm -r "$(dirname "$cc1plus")/cc1" "$(dirname "$cc1plus")/lto1" "$plugin" && \
    rm -f /usr/bin/lto-dump /usr/bin/gcov /usr/bin/gcov-dump /usr/bin/gcov-tool /usr/bin/dwp \
        /usr/lib/libc.a /usr/lib/libstdc++.a /usr/bin/verilator_bin_dbg /usr/bin/verilator_coverage_bin_dbg && \
    # Graphviz: only dot's SVG output is used (its core and dot-layout plugins); the image-format and
    # window-system plugins go, with the libraries that only they load (about 25 MB)
    cd /usr/lib/graphviz && \
    rm -f libgvplugin_gd.so* libgvplugin_kitty.so* libgvplugin_neato_layout.so* libgvplugin_pango.so* \
        libgvplugin_vt.so* libgvplugin_webp.so* libgvplugin_xlib.so* config6 && \
    dot -c && cd / && \
    libs="aom-libs libdav1d libyuv libavif libheif libwebp libsharpyuv libgd libtiff libjpeg-turbo \
        cairo pango harfbuzz libx11 libxcb libxext libxrender libxft libxpm libxau libxdmcp pixman \
        graphite2 fontconfig freetype libpng glib libbsd libmd libxml2 libjxl libde265 x265-libs lerc \
        libdeflate gdk-pixbuf" && \
    for p in $libs; do \
        apk info -L "$p" 2>/dev/null | grep '^usr/lib/.*\.so' | sed 's|^|/|' | xargs -r rm -f; \
    done && \
    # (what the course runs must still find every library, and dot must not warn)
    for f in /usr/bin/dot /usr/lib/graphviz/*.so.6 $(find /usr/lib/python3.12 -name '*.so') \
        /usr/bin/verilator_bin "$cc1plus" /usr/bin/as /usr/bin/ld /usr/bin/make; do \
        if ldd "$f" 2>&1 | grep -q 'Error loading'; then ldd "$f"; exit 1; fi; \
    done && \
    test -z "$(echo 'digraph { a -> b }' | dot -Tsvg 2>&1 >/dev/null)" && \
    # The `verilator` wrapper, without Perl: for a normal run, the Perl one sets VERILATOR_ROOT,
    # counts its re-entries, lifts the stack limit and runs verilator_bin with the same arguments.
    # (Lost: its --help text, --gdb, --valgrind and verilator_coverage, which the course does not
    # use.) Then Perl goes (about 40 MB), with the Perl trampolines in Verilator's own bin/.
    printf '%s\n' '#!/bin/sh' \
        '# Stands in for Verilator'"'"'s Perl wrapper (see the Dockerfile)' \
        'if [ "$1" = --help ]; then echo "See https://verilator.org/guide/latest/exe_verilator.html"; exit 0; fi' \
        'export VERILATOR_ROOT="${VERILATOR_ROOT:-/usr/share/verilator}"' \
        'export VERILATOR_RUNNING=$(( ${VERILATOR_RUNNING:-0} + 1 ))' \
        'ulimit -s unlimited 2>/dev/null' \
        'exec /usr/bin/verilator_bin "$@"' > /usr/bin/verilator && \
    cd /usr/share/verilator/bin && \
    rm verilator verilator_bin verilator_bin_dbg verilator_coverage verilator_coverage_bin_dbg \
        verilator_gantt verilator_profcfunc /usr/bin/verilator_coverage && \
    ln -s /usr/bin/verilator verilator && ln -s /usr/bin/verilator_bin verilator_bin && \
    ln -s /usr/bin/verilator_gantt verilator_gantt && ln -s /usr/bin/verilator_profcfunc verilator_profcfunc && \
    apk info -L perl | grep '^usr/' | sed 's|^|/|' | xargs rm -rf && \
    rm -rf /usr/lib/perl5 /usr/share/perl5 && \
    cd / && \
    # On amd64, the C++ compiler and Verilator compressed with UPX: 35 MB less, and they unpack in a
    # fraction of a second. Not on Arm64, where UPX 5.0 packs programs that fail to start in a large
    # environment (upx/upx#952, fixed in 5.1).
    if [ "$TARGETARCH" = "amd64" ]; then \
        apk add --no-cache --virtual .pack upx && upx -q "$cc1plus" /usr/bin/verilator_bin && \
        apk del --no-cache .pack && \
        echo 'int main() {}' | g++ -x c++ - -o /tmp/a.out && /tmp/a.out && rm /tmp/a.out && \
        verilator --version; \
    fi && \
    # apk still lists the packages that lost files above as installed; an image built on this one
    # that adds packages restores them first: apk fix --no-cache $(cat /etc/bootcamp-pruned)
    # (apk info -e prints those installed, and exits with the number of the others)
    { apk info -e perl verilator graphviz gcc g++ musl-dev libstdc++-dev python3 $libs || true; } \
        > /etc/bootcamp-pruned && \
    grep -qx perl /etc/bootcamp-pruned

COPY --from=jre /opt/java /usr/lib/jvm/java-17-openjdk
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk
ENV PATH=$JAVA_HOME/bin:$PATH

RUN for f in "$JAVA_HOME"/lib/*.so "$JAVA_HOME"/lib/server/*.so; do \
        if LD_LIBRARY_PATH="$JAVA_HOME/lib:$JAVA_HOME/lib/server" ldd "$f" 2>&1 | grep -q 'Error loading'; then \
            ldd "$f"; exit 1; \
        fi; \
    done && \
    ln -s "$JAVA_HOME/bin/java" /usr/bin/java && \
    ln -sf /etc/ssl/certs/java/cacerts "$JAVA_HOME/lib/security/cacerts" && \
    adduser -D -s /bin/bash bootcamp

ENV SCALA_VERSION=2.13.18
ENV ALMOND_VERSION=0.14.5
ENV COURSIER_VERSION=2.1.24
# sha256 of the coursier launcher of its GitHub release; update together with COURSIER_VERSION
ENV COURSIER_SHA256=7aa975f12469726d6bb87852107afe0b8d6f3c82b12a49ef43cc6647c4e057ca
ENV SBT_VERSION=1.13.1
# sha256 of sbt-$SBT_VERSION.tgz (from its GitHub release); update together with SBT_VERSION
ENV SBT_SHA256=4a20e36c1642b6d5be34ef641d2ed6c8c0d464e6145283730d5566716e4083be
ENV CHISEL_VERSION=7.16.0
# Must be the firtool version that Chisel $CHISEL_VERSION was published against (checked by
# tools/check-versions.py, which also makes you revisit what 2.8_memories.ipynb says about how
# this firtool builds a SyncReadMem).
ENV FIRTOOL_VERSION=1.160.0
# sha256 of llvm-firtool-$FIRTOOL_VERSION-linux-x64.jar (Maven Central); update together
ENV FIRTOOL_JAR_SHA256=8f66a76dd7e48dd3b5134bafa091e467d90d4aef2f85b0ebcf3659435f9b22e6
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

# Ammonite, sbt and the build stage use this preloaded cache (coursier/), beside sbt's own files
# (sbt/): one directory, so that the jars both hold are stored once (see the last stage)
ENV COURSIER_CACHE=/opt/cache/coursier
ENV PATH=/opt/sbt/bin:$PATH

# Second stage - download Scala requirements and the Scala kernel
FROM base AS intermediate-builder

ARG TARGETARCH
ARG BUILDARCH

# Download everything into the cache that the final image uses at runtime
RUN mkdir -p /opt/cache/coursier

# The JVM launcher of coursier works on every architecture.
RUN \
    apk add --no-cache curl unzip && \
    curl -fL --retry 3 -o coursier \
        "https://github.com/coursier/coursier/releases/download/v${COURSIER_VERSION}/coursier" && \
    echo "$COURSIER_SHA256  coursier" | sha256sum -c - && \
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
    echo "$FIRTOOL_JAR_SHA256  firtool.jar" | sha256sum -c - && \
    unzip -p firtool.jar org.chipsalliance/llvm-firtool/linux-x64/bin/firtool > /opt/firtool/bin/firtool.x86_64 && \
    rm firtool.jar && \
    chmod +x /opt/firtool/bin/firtool.x86_64 && \
    # Dropping its symbols and compressing it with UPX take it from 53 to 10 MB, for about 0.2 s more
    # per run; on Arm64, where firtool runs under qemu, neither (Arm64's binutils cannot read an
    # x86-64 executable, and unpacking under emulation would be slow)
    if [ "$TARGETARCH" = "amd64" ]; then \
        apk add --no-cache --virtual .pack upx && strip /opt/firtool/bin/firtool.x86_64 && \
        upx -q /opt/firtool/bin/firtool.x86_64 && apk del --no-cache .pack; \
    fi && \
    if [ "$TARGETARCH" = "arm64" ]; then \
        printf '#!/bin/sh\nexec qemu-x86_64 /opt/firtool/bin/firtool.x86_64 "$@"\n' > /opt/firtool/bin/firtool && \
        chmod +x /opt/firtool/bin/firtool; \
    else \
        mv /opt/firtool/bin/firtool.x86_64 /opt/firtool/bin/firtool; \
    fi

# sbt for the riscv/ project (Module 5). sbt runs on the JRE: the project has no Java sources.
# Its own files go to /opt/cache/sbt (set in sbt's configuration file, so every user gets it).
RUN \
    curl -fL --retry 3 -o sbt.tgz "https://github.com/sbt/sbt/releases/download/v${SBT_VERSION}/sbt-${SBT_VERSION}.tgz" && \
    echo "$SBT_SHA256  sbt.tgz" | sha256sum -c - && \
    tar -xzf sbt.tgz -C /opt && \
    rm sbt.tgz /opt/sbt/bin/sbtn-* /opt/sbt/bin/sbt.bat && \
    printf '%s\n' -Dsbt.global.base=/opt/cache/sbt/global -Dsbt.boot.directory=/opt/cache/sbt/boot \
        -Dsbt.ivy.home=/opt/cache/sbt/ivy -Dsbt.server.forcestart=false >> /opt/sbt/conf/sbtopts

# Preload what `sbt test` needs offline: the sbt runtime and the Scala 2.12 bridge that compiles
# build.sbt (/opt/cache/sbt), and the project's dependencies (/opt/cache/coursier). Only the build
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
    cd / && rm -rf /tmp/riscv /opt/cache/sbt/global/server /opt/cache/sbt/global/java9-rt-ext-* && \
    # sbt and coursier hold many of the same jars (75 MB): hard links store each once
    apk add --no-cache util-linux-misc && hardlink -q -t /opt/cache # (-t: whatever their times)

# Last stage
FROM base AS final

# Tell Chisel where to find the preinstalled firtool instead of downloading one
ENV CHISEL_FIRTOOL_PATH=/opt/firtool/bin

# copy the Scala requirements and kernel into the image
COPY --from=intermediate-builder --chown=bootcamp:bootcamp /opt/cache/ /opt/cache/
COPY --from=intermediate-builder --chown=bootcamp:bootcamp /usr/local/share/jupyter/kernels/scala/ /usr/local/share/jupyter/kernels/scala/
COPY --from=intermediate-builder /opt/firtool/ /opt/firtool/
COPY --from=intermediate-builder /opt/sbt/ /opt/sbt/

COPY --chown=bootcamp:bootcamp . /chisel-bootcamp/

# The directory itself is root-owned (created by WORKDIR); ChiselSim writes ./build and Jupyter
# writes checkpoints there.
RUN chown -R bootcamp:bootcamp /jupyter && chown bootcamp:bootcamp /chisel-bootcamp

USER bootcamp
WORKDIR /chisel-bootcamp

EXPOSE 8888
CMD ["jupyter", "lab", "--no-browser", "--ip", "0.0.0.0", "--port", "8888"]
