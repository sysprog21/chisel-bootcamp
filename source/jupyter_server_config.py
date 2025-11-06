# Jupyter Server Configuration
# Optimized for Docker container use with minimal warnings

c = get_config()  #noqa

# Fix websocket ping timeout warning
c.ServerApp.websocket_ping_interval = 30
c.ServerApp.websocket_ping_timeout = 30

# Disable authentication for local Docker use
# This eliminates cookie expiration, 403, and WebSocket auth warnings
c.ServerApp.disable_check_xsrf = True
c.IdentityProvider.token = ''
c.ServerApp.allow_origin = '*'
c.ServerApp.allow_credentials = True

# Keep INFO level for essential startup messages (URL, port, etc)
# but suppress specific noisy loggers
c.ServerApp.log_level = 'INFO'

# Suppress tornado HTTP warnings (403, cookie errors)
import logging
logging.getLogger('tornado.access').setLevel(logging.ERROR)
logging.getLogger('tornado.application').setLevel(logging.ERROR)

# Create a custom filter to suppress specific warning messages
class SuppressAuthWarning(logging.Filter):
    def filter(self, record):
        return 'All authentication is disabled' not in record.getMessage()

# Apply the filter to ServerApp logger
server_logger = logging.getLogger('ServerApp')
server_logger.addFilter(SuppressAuthWarning())

# Hide system and build files from file browser
# Students only need to see notebook files (*.ipynb)
c.ContentsManager.hide_globs = [
    'almond', 'coursier', 'Dockerfile', 'LICENSE',
    'Install.md', 'README.md', 'runtest.py',
    'binder', 'images', 'source'
]
