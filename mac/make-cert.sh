#!/bin/zsh
# One-time: creates a self-signed code-signing identity "PhoneBridge Local Signing" in the login keychain.
# Signing every build with the same identity keeps macOS permissions (Accessibility, Bluetooth, …) across rebuilds.
set -e
NAME="PhoneBridge Local Signing"
if security find-certificate -c "$NAME" >/dev/null 2>&1; then echo "Already exists: $NAME"; exit 0; fi
TMP=$(mktemp -d)
cat > "$TMP/cfg" <<CFG
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = $NAME
[ext]
basicConstraints = critical,CA:false
keyUsage = critical,digitalSignature
extendedKeyUsage = critical,codeSigning
CFG
openssl req -x509 -newkey rsa:2048 -nodes -days 3650 -keyout "$TMP/key.pem" -out "$TMP/cert.pem" -config "$TMP/cfg" 2>/dev/null
openssl pkcs12 -export -legacy -inkey "$TMP/key.pem" -in "$TMP/cert.pem" -out "$TMP/id.p12" -passout pass:phonebridge -name "$NAME"
security import "$TMP/id.p12" -k ~/Library/Keychains/login.keychain-db -P phonebridge -T /usr/bin/codesign
rm -rf "$TMP"
echo "Created: $NAME"
