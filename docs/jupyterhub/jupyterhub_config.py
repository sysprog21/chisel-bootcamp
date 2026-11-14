# The parts of a jupyterhub_config.py that the bootcamp needs (DockerSpawner); add the hub's
# authenticator and network settings.

c = get_config()  # noqa

c.JupyterHub.spawner_class = "dockerspawner.DockerSpawner"
c.DockerSpawner.image = "bootcamp-hub"  # built from docs/jupyterhub/Dockerfile
c.DockerSpawner.notebook_dir = "/chisel-bootcamp"
# one named volume per user: Docker fills it from the image the first time
c.DockerSpawner.volumes = {"bootcamp-{username}": "/chisel-bootcamp"}
c.DockerSpawner.mem_limit = "4G"  # a JVM beside Verilator's C++ compiler (docs/troubleshooting.md)
c.DockerSpawner.cpu_limit = 2
