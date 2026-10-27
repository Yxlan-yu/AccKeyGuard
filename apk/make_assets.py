"""Generate launcher icons + self-signed PKCS12 keystore for the AccGuard APK project."""
import os
from PIL import Image, ImageDraw

BASE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(BASE, "app", "src", "main", "res")
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

BG = (11, 13, 17, 255)
ACCENT = (76, 141, 255, 255)
OK = (53, 208, 127, 255)


def draw_icon(px):
    ss = 8
    n = px * ss
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    r = int(n * 0.22)
    d.rounded_rectangle([0, 0, n - 1, n - 1], radius=r, fill=BG)

    # outer ring with a gap (signal / guard)
    cx = cy = n / 2.0
    rad = n * 0.30
    w = max(2, int(n * 0.075))
    box = [cx - rad, cy - rad, cx + rad, cy + rad]
    d.arc(box, start=118, end=430, fill=ACCENT, width=w)

    # inner dot
    ir = n * 0.095
    d.ellipse([cx - ir, cy - ir, cx + ir, cy + ir], fill=OK)

    return img.resize((px, px), Image.LANCZOS)


for dens, px in SIZES.items():
    outdir = os.path.join(RES, "mipmap-" + dens)
    os.makedirs(outdir, exist_ok=True)
    p = os.path.join(outdir, "ic_launcher.png")
    draw_icon(px).save(p, "PNG")
    print("icon", dens, px, os.path.getsize(p))


# ---- keystore ----
import datetime
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives.serialization import pkcs12

PASS = b"akg2026guard"
ALIAS = "akg"

key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
subject = issuer = x509.Name([
    x509.NameAttribute(NameOID.COMMON_NAME, "AccGuard"),
    x509.NameAttribute(NameOID.ORGANIZATION_NAME, "Yxlan-yu"),
    x509.NameAttribute(NameOID.COUNTRY_NAME, "CN"),
])
now = datetime.datetime.now(datetime.timezone.utc)
cert = (
    x509.CertificateBuilder()
    .subject_name(subject)
    .issuer_name(issuer)
    .public_key(key.public_key())
    .serial_number(x509.random_serial_number())
    .not_valid_before(now - datetime.timedelta(days=2))
    .not_valid_after(now + datetime.timedelta(days=365 * 30))
    .add_extension(x509.BasicConstraints(ca=True, path_length=None), critical=True)
    .sign(key, hashes.SHA256())
)

friendly = ALIAS.encode("utf-8")
data = pkcs12.serialize_key_and_certificates(
    friendly, key, cert, None,
    serialization.BestAvailableEncryption(PASS),
)
ks_path = os.path.join(BASE, "app", "akg.keystore")
with open(ks_path, "wb") as f:
    f.write(data)
print("keystore", os.path.getsize(ks_path))

# read-back verification
_k, _c, _ca = pkcs12.load_key_and_certificates(open(ks_path, "rb").read(), PASS)
print("readback", _c.subject.rfc2822(), "cn=", _c.subject.get_attributes_for_oid(NameOID.COMMON_NAME)[0].value)
print("serial", hex(_c.serial_number))

cert_der = cert.public_bytes(serialization.Encoding.DER)
print("cert_der_sha256", __import__("hashlib").sha256(cert_der).hexdigest())
