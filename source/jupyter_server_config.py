# Jupyter Server Configuration
# Optimized for Docker container use with minimal warnings

c = get_config()  #noqa

# Fix websocket ping timeout warning
# Set both to same value to avoid timeout > interval warning
c.ServerApp.websocket_ping_interval = 30
c.ServerApp.websocket_ping_timeout = 30

# Disable authentication for local Docker use
# This eliminates cookie expiration, 403, and WebSocket auth warnings
c.ServerApp.disable_check_xsrf = True
c.IdentityProvider.token = ''
c.ServerApp.allow_origin = '*'
c.ServerApp.allow_credentials = True

# Disable history manager to suppress Almond kernel history_request errors
# Almond kernel doesn't support history operations, causing JSON decode errors
c.HistoryManager.enabled = False

# Disable notebook trust warnings in container environment
# All notebooks are from trusted source (bootcamp materials)
c.ServerApp.trust_xheaders = False

# Keep INFO level for essential startup messages (URL, port, etc)
# but suppress specific noisy loggers
c.ServerApp.log_level = 'INFO'

# Suppress tornado HTTP warnings (403, cookie errors)
import logging
logging.getLogger('tornado.access').setLevel(logging.ERROR)
logging.getLogger('tornado.application').setLevel(logging.ERROR)

# Suppress LabApp schema validation warnings
logging.getLogger('LabApp').setLevel(logging.ERROR)

# Create a custom filter to suppress specific warning messages
class SuppressWarnings(logging.Filter):
    def filter(self, record):
        # Only filter WARNING level messages, not INFO
        if record.levelno != logging.WARNING:
            return True

        msg = record.getMessage()
        # Suppress specific warnings
        return not any([
            'All authentication is disabled' in msg,
            'Clearing invalid/expired login cookie' in msg,
            'is not trusted' in msg,
            'Could not determine jupyterlab build status' in msg
        ])

# Apply the filter to ServerApp logger
server_logger = logging.getLogger('ServerApp')
server_logger.addFilter(SuppressWarnings())

# Hide system and build files from file browser
# Students only need to see notebook files (*.ipynb)
c.ContentsManager.hide_globs = [
    'almond', 'coursier', 'Dockerfile', 'LICENSE',
    'Install.md', 'README.md', 'runtest.py',
    'binder', 'images', 'source'
]
