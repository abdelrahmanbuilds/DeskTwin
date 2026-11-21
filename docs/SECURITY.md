# Security and privacy

DeskTwin is designed for use on a trusted local network. The Windows PC hosts the service; the Android app pairs to it using a QR code containing a short-lived pairing code, local address, and TLS certificate fingerprint.

- Android verifies the pinned TLS certificate when pairing. If the Windows certificate changes, pair again by scanning a new QR code.
- Windows shares only the folders selected by the user. Android cannot browse arbitrary PC paths outside the configured scope.
- Windows can browse a phone folder only after the user selects it through Android's Storage Access Framework. This does not grant unrestricted access to phone storage.
- File and device-control actions may require Windows approval or an elevated trust level.
- DeskTwin uses local HTTPS/WebSocket connections for device communication. Local network availability is required; firewall policy, VPNs, and access-point isolation may prevent connections.
- The project does not require a cloud account for pairing or file transfer.

Report security issues privately to the repository owner. Do not include pairing QR codes, private keys, personal files, logs containing file names, or network addresses in public issue reports.
