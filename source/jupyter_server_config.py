# Jupyter Server Configuration
# This configuration disables history requests to prevent Almond kernel
# JSON decoding errors that are harmless but annoying

c = get_config()  #noqa

# Disable history access to prevent history_request messages
# that cause JSON decoding errors in Almond 0.9.1
c.HistoryManager.enabled = False
c.HistoryManager.hist_file = ':memory:'

# Optional: Reduce logging noise
c.Application.log_level = 'WARN'
