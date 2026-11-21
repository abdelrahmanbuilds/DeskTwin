# Python service

The Python service implements the local DeskTwin API, TLS pairing, file access, device discovery, transfers, and the WinUI bridge. It runs on the Windows PC; Android clients connect over the trusted local network.

Install the desktop dependency set from the Windows project root:

    python -m pip install -r Python/dependencies/requirements-winui.txt

For dependency layering, requirements-winui.txt includes requirements-core.txt. The requirements-python-gui.txt file is retained for the legacy CustomTkinter interface and is not needed by the current WinUI app.

Local configuration, databases, and diagnostics are created at runtime and should not be committed.
