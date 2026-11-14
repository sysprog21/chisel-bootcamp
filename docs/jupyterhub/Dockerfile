# The bootcamp image for a JupyterHub (DockerSpawner): adds the single-user server, and turns back
# on the checks that the image's server configuration (one person on a laptop) turns off.
# Build: docker build --build-arg JUPYTERHUB_VERSION=<the hub's version> -t bootcamp-hub docs/jupyterhub
ARG BOOTCAMP_IMAGE=sysprog21/chisel-bootcamp:chisel-7.16.0
FROM ${BOOTCAMP_IMAGE}
ARG JUPYTERHUB_VERSION
USER root
# the image deletes pip after its own installs: restore Alpine's py3-pip, then install the hub's
# single-user server
RUN test -n "$JUPYTERHUB_VERSION" && \
    apk fix --no-cache py3-pip && \
    pip3 install --no-cache-dir --break-system-packages "jupyterhub==${JUPYTERHUB_VERSION}"
COPY hub_server_config.py /tmp/hub_server_config.py
RUN cat /tmp/hub_server_config.py >> /jupyter/config/jupyter_server_config.py && rm /tmp/hub_server_config.py
USER bootcamp
CMD ["jupyterhub-singleuser"]
