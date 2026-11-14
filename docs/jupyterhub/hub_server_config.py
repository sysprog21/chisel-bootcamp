# Appended to the image's Jupyter Server configuration (/jupyter/config/jupyter_server_config.py)
# by docs/jupyterhub/Dockerfile: on a hub, which authenticates users, these settings, which come
# later, turn the checks back on that the image's configuration turns off for one person on a
# laptop. The rest of that configuration (hidden files, log filters) still applies.

c.ServerApp.disable_check_xsrf = False
c.ServerApp.allow_origin = ""
c.ServerApp.allow_credentials = False
