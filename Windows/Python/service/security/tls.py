import hashlib
import ipaddress
import os
from datetime import datetime, timedelta, timezone

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID


def certificate_paths() -> tuple[str, str]:
    root = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "DeskTwin")
    os.makedirs(root, exist_ok=True)
    return os.path.join(root, "server.crt"), os.path.join(root, "server.key")


def ensure_certificate() -> tuple[str, str, str]:
    cert_path, key_path = certificate_paths()
    if not (os.path.isfile(cert_path) and os.path.isfile(key_path)):
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        subject = issuer = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "DeskTwin")])
        cert = (
            x509.CertificateBuilder().subject_name(subject).issuer_name(issuer)
            .public_key(key.public_key()).serial_number(x509.random_serial_number())
            .not_valid_before(datetime.now(timezone.utc) - timedelta(minutes=1))
            .not_valid_after(datetime.now(timezone.utc) + timedelta(days=3650))
            .add_extension(x509.SubjectAlternativeName([x509.DNSName("DeskTwin"), x509.IPAddress(ipaddress.ip_address("127.0.0.1"))]), critical=False)
            .sign(key, hashes.SHA256())
        )
        with open(key_path, "wb") as f:
            f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.TraditionalOpenSSL, serialization.NoEncryption()))
        with open(cert_path, "wb") as f:
            f.write(cert.public_bytes(serialization.Encoding.PEM))
    with open(cert_path, "rb") as f:
        fingerprint = hashlib.sha256(x509.load_pem_x509_certificate(f.read()).public_bytes(serialization.Encoding.DER)).hexdigest()
    return cert_path, key_path, fingerprint
