#!/usr/bin/env python3
"""Give local production builds a persistent, certificate-backed macOS identity.

The private key stays in a dedicated user keychain. No root trust, privacy grants,
or Gatekeeper settings are changed. Developer ID builds use the release signer.
"""
import fcntl
import os
from pathlib import Path
import secrets
import shlex
import subprocess
import sys
import tempfile


def run(*args):
    result = subprocess.run(args, text=True, capture_output=True)
    if result.returncode:
        # Do not echo arguments: security commands contain the keychain password.
        raise RuntimeError(f"{args[0]} failed: {result.stderr.strip()}")
    return result.stdout


def sign_app(app, helpers, state):
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    with (state / "lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        keychain = str(state / "signing.keychain-db")
        password_file = state / "password"
        certificate = state / "certificate.pem"
        if not password_file.exists():
            if Path(keychain).exists() or certificate.exists():
                raise RuntimeError("Incomplete local signing setup; restore the signing directory from backup.")
            password_file.write_text(secrets.token_hex(32))
            password_file.chmod(0o600)
        password = password_file.read_text().strip()
        previous = shlex.split(run("security", "list-keychains", "-d", "user"))
        try:
            if not Path(keychain).exists():
                run("security", "create-keychain", "-p", password, keychain)
                run("security", "set-keychain-settings", "-lut", "21600", keychain)
            run("security", "unlock-keychain", "-p", password, keychain)
            # codesign needs the keychain in the search list even with --keychain.
            run("security", "list-keychains", "-d", "user", "-s", *dict.fromkeys([*previous, keychain]))
            if not certificate.exists():
                with tempfile.TemporaryDirectory(dir=state) as tmp:
                    tmp = Path(tmp)
                    config = tmp / "certificate.cnf"
                    config.write_text(
                        "[req]\ndistinguished_name=dn\nx509_extensions=ext\nprompt=no\n"
                        "[dn]\nCN=FirstPick Local Development\n"
                        "[ext]\nbasicConstraints=critical,CA:false\n"
                        "keyUsage=critical,digitalSignature\nextendedKeyUsage=critical,codeSigning\n"
                    )
                    run("/usr/bin/openssl", "req", "-new", "-x509", "-newkey", "rsa:2048", "-nodes",
                        "-days", "3650", "-config", str(config), "-keyout", str(tmp / "key.pem"),
                        "-out", str(tmp / "certificate.pem"))
                    run("/usr/bin/openssl", "pkcs12", "-export", "-inkey", str(tmp / "key.pem"),
                        "-in", str(tmp / "certificate.pem"), "-out", str(tmp / "identity.p12"),
                        "-passout", "pass:" + password)
                    run("security", "import", str(tmp / "identity.p12"), "-k", keychain,
                        "-P", password, "-T", "/usr/bin/codesign")
                    run("security", "set-key-partition-list", "-S", "apple-tool:,apple:", "-k", password, keychain)
                    (tmp / "certificate.pem").replace(certificate)
            fingerprint = run("/usr/bin/openssl", "x509", "-in", str(certificate), "-noout", "-fingerprint", "-sha1")
            fingerprint = fingerprint.strip().split("=")[1].replace(":", "")
            bundled = app / "Contents/Helpers"
            bundled.mkdir(exist_ok=True)
            for name in ("window-locator", "window-capture", "vision-ocr", "deck-reader"):
                run("ditto", str(helpers / name), str(bundled / name))
                sign(bundled / name, "com.firstpick.app." + name, fingerprint, keychain)
            sign(app, "com.firstpick.app", fingerprint, keychain, preserve=True)
            run("codesign", "--verify", "--deep", "--strict", str(app))
            print("Local production identity: " + fingerprint)
        finally:
            run("security", "list-keychains", "-d", "user", "-s", *previous)
            run("security", "lock-keychain", keychain)


def sign(path, identifier, fingerprint, keychain, preserve=False):
    requirement = f'designated => identifier "{identifier}" and certificate leaf = H"{fingerprint}"'
    args = ["codesign", "--force", "--sign", fingerprint, "--keychain", keychain,
            "--identifier", identifier, "--requirements", "=" + requirement,
            "--timestamp=none", "--options", "runtime"]
    if preserve:
        args.append("--preserve-metadata=entitlements")
    run(*args, str(path))
    run("codesign", "--verify", "--strict", str(path))


if __name__ == "__main__":
    os.umask(0o077)
    try:
        sign_app(Path(sys.argv[1]).resolve(), Path(sys.argv[2]).resolve(),
                 Path.home() / "Library/Application Support/FirstPick/signing")
    except (RuntimeError, OSError) as error:
        sys.exit(str(error))
