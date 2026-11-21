# Windows service tests

The unittest suite covers service API behavior, pairing, file operations, transfer handling, discovery, phone file access, and bridge integration.

From the Windows project root, run:

    python -m unittest discover -s tests -v

Tests do not require the local user configuration or runtime database.
