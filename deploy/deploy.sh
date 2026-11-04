#!/usr/bin/env bash
# Upload server + PWA and (re)start the containers; with --apk also build and publish the Android app.
#   deploy/deploy.sh            server + PWA (the Android app on the server stays as it is)
#   deploy/deploy.sh --apk      also a new release APK - only when the user explicitly asks for it
#   deploy/deploy.sh --admin    also the admin app (android/admin) at /admin.apk (flags can be combined)
#   (from the project root, Git Bash)
set -euo pipefail
APK=0
ADMIN=0
for arg in "$@"; do
    [ "$arg" = "--apk" ] && APK=1
    [ "$arg" = "--admin" ] && ADMIN=1
done
HOST=sz-dev-environments
DIR=/opt/momentum
cd "$(dirname "$0")/.."

[ "$APK" = 1 ] && (cd android && ./gradlew.bat :app:assembleRelease -PmomentumServer=https://momentum.mal-zeta.de -q)
[ "$ADMIN" = 1 ] && (cd android && ./gradlew.bat :admin:assembleRelease -PmomentumServer=https://momentum.mal-zeta.de -q)
# the PWA for iOS (same server, served at /app/)
(cd web && npm ci --no-audit --no-fund --silent && npm run build --silent)

tar czf /tmp/momentum-deploy.tgz \
    --exclude='__pycache__' --exclude='server/tests' --exclude='server/momentum.db' --exclude='server/momentum.db-*' \
    -C deploy Dockerfile docker-compose.yml \
    -C .. server pixelart/out web/dist
ssh "$HOST" "mkdir -p $DIR/apk $DIR/apk-admin"
scp -q /tmp/momentum-deploy.tgz "$HOST:/tmp/momentum-deploy.tgz"
# APK + its version (output-metadata.json): apps older than this are asked to update
if [ "$APK" = 1 ]; then
    scp -q android/app/build/outputs/apk/release/app-release.apk     android/app/build/outputs/apk/release/output-metadata.json "$HOST:$DIR/apk/"
fi
if [ "$ADMIN" = 1 ]; then
    scp -q android/admin/build/outputs/apk/release/admin-release.apk "$HOST:$DIR/apk-admin/"
fi
ssh "$HOST" bash -s <<REMOTE
set -euo pipefail
cd $DIR
tar xzf /tmp/momentum-deploy.tgz && rm /tmp/momentum-deploy.tgz
# secrets are created once and kept
[ -f .env ] || printf 'MINIO_USER=momentum\nMINIO_PASSWORD=%s\n' "\$(openssl rand -hex 24)" > .env
# calls: the TURN relay's shared secret, its public address and name (server/calls.py, coturn in the compose file)
grep -q '^TURN_SECRET=' .env || printf 'TURN_SECRET=%s\n' "\$(openssl rand -hex 32)" >> .env
grep -q '^TURN_IP=' .env || printf 'TURN_IP=%s\n' "\$(ip -4 route get 1.1.1.1 | awk '{for (i = 1; i < NF; i++) if (\$i == "src") print \$(i + 1)}')" >> .env
grep -q '^TURN_HOST=' .env || printf 'TURN_HOST=momentum.mal-zeta.de\n' >> .env
chmod 600 .env
docker compose up -d --build
docker compose ps
REMOTE
