#!/usr/bin/env python3
"""Generate a salted demo account setting without storing a password."""
import argparse
import getpass
import hashlib
import re
import secrets

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("name")
parser.add_argument("role", choices=["OPERATOR", "PICKER", "SUPERVISOR"])
parser.add_argument("branch")
args = parser.parse_args()
if not all(re.fullmatch(r"[A-Za-z0-9_-]{1,40}", value) for value in (args.name, args.branch)):
    parser.error("Name and branch must be 1–40 ASCII letters, digits, underscores or hyphens")
password = getpass.getpass("Workspace password (12–128 printable ASCII characters): ")
if not (12 <= len(password) <= 128 and re.fullmatch(r"[ -~]+", password)):
    parser.error("Password must be 12–128 printable ASCII characters")
if password != getpass.getpass("Repeat password: "):
    parser.error("Passwords did not match")
salt = secrets.token_bytes(16)
digest = hashlib.pbkdf2_hmac("sha256", password.encode("ascii"), salt, 600_000).hex()
print(",".join([args.name, args.role, args.branch, salt.hex(), digest]))
