#!/usr/bin/env bash
#
# Copyright (C) 2026 Danilo M. <danix@danix.xyz>
# SPDX-License-Identifier: GPL-3.0-only
#
# Disposable end-to-end test environment for the app running in an emulator:
# a git-over-SSH server in a Docker container, a throwaway SSH key, a
# throwaway OpenPGP key and a small password store encrypted to it.
#
# Everything generated lives in .e2e/ (gitignored). Nothing here touches a
# real password store or real keys. From the emulator the server is
#   ssh://git@10.0.2.2:2222/srv/git/store.git
#
# Usage: scripts/e2e-env.sh up|down|status|push-keys

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STATE="$ROOT/.e2e"
NAME="aps-e2e-git"
PORT=2222
GPG_UID="APS Test <aps.test@example.org>"
GPG_PASS="test"
export GNUPGHOME="$STATE/gnupg"

die() { echo "e2e-env: $*" >&2; exit 1; }

gen_keys() {
  mkdir -p "$STATE"
  [[ -f "$STATE/id_ed25519" ]] || ssh-keygen -q -t ed25519 -N '' -C aps-e2e -f "$STATE/id_ed25519"
  if [[ ! -f "$STATE/test-secret.asc" ]]; then
    mkdir -p "$GNUPGHOME" && chmod 700 "$GNUPGHOME"
    gpg -q --batch --pinentry-mode loopback --passphrase "$GPG_PASS" \
      --quick-gen-key "$GPG_UID" default default never
    gpg -q --batch --pinentry-mode loopback --passphrase "$GPG_PASS" \
      --armor --export-secret-keys "$GPG_UID" > "$STATE/test-secret.asc"
  fi
}

# Build a small pass-compatible store, encrypted to the test key, as the
# initial commit of the server's repository.
seed_store() {
  local work="$STATE/seed"
  rm -rf "$work" && mkdir -p "$work"
  gpg --with-colons --list-keys "$GPG_UID" | awk -F: '/^fpr/ {print $10; exit}' > "$work/.gpg-id"
  enc() {
    mkdir -p "$work/$(dirname "$1")"
    printf '%b' "$2" | gpg -q --batch --trust-model always -e -r "$GPG_UID" -o "$work/$1.gpg"
  }
  enc example.org "hunter2\nusername: alice\nurl: https://example.org\n"
  enc work/mail.example.org "correct horse battery staple\nlogin: bob\n"
  enc otp/example.com "s3cret\notpauth://totp/example.com:carol?secret=JBSWY3DPEHPK3PXP&issuer=example.com\n"
  git -C "$work" init -q -b master
  git -C "$work" -c user.name=aps-e2e -c user.email=aps.test@example.org \
    -c commit.gpgsign=false add -A
  git -C "$work" -c user.name=aps-e2e -c user.email=aps.test@example.org \
    -c commit.gpgsign=false commit -q --no-verify -m "Initial test store"
}

up() {
  command -v docker >/dev/null || die "docker not found"
  gen_keys
  seed_store
  docker rm -f "$NAME" >/dev/null 2>&1 || true
  docker build -q -t "$NAME" - >/dev/null <<'EOF'
FROM alpine:3
RUN apk add --no-cache openssh git \
 && adduser -D -s /usr/bin/git-shell git \
 && passwd -u git \
 && ssh-keygen -A \
 && mkdir -p /srv/git && chown git:git /srv/git
CMD ["/usr/sbin/sshd", "-D", "-e"]
EOF
  docker run -d --name "$NAME" -p "127.0.0.1:$PORT:22" "$NAME" >/dev/null
  docker exec -i "$NAME" sh -c "mkdir -p /home/git/.ssh && cat > /home/git/.ssh/authorized_keys \
    && chown -R git:git /home/git/.ssh && chmod 700 /home/git/.ssh && chmod 600 /home/git/.ssh/authorized_keys" \
    < "$STATE/id_ed25519.pub"
  docker exec -u git "$NAME" git init -q --bare -b master /srv/git/store.git
  GIT_SSH_COMMAND="ssh -F /dev/null -i $STATE/id_ed25519 -p $PORT -o IdentitiesOnly=yes -o IdentityAgent=none -o BatchMode=yes -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR" \
    git -C "$STATE/seed" push -q "ssh://git@127.0.0.1/srv/git/store.git" master
  status
}

down() { docker rm -f "$NAME" >/dev/null 2>&1 && echo "stopped $NAME" || echo "$NAME not running"; }

status() {
  docker ps --filter "name=^$NAME$" --format '{{.Names}}: {{.Status}}' | grep . || echo "$NAME not running"
  echo "server (from emulator): ssh://git@10.0.2.2:$PORT/srv/git/store.git"
  echo "test keys: $STATE/id_ed25519, $STATE/test-secret.asc (passphrase: $GPG_PASS)"
}

# Copy the throwaway keys to the emulator so the app can import them.
push-keys() {
  adb push "$STATE/id_ed25519" /sdcard/Download/aps-e2e-ssh-key
  adb push "$STATE/test-secret.asc" /sdcard/Download/aps-e2e-pgp-key.asc
}

case "${1:-}" in
  up | down | status | push-keys) "$1" ;;
  *) die "usage: $0 up|down|status|push-keys" ;;
esac
