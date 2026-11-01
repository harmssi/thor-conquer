#!/bin/bash
# Thor Conquer bauen: Quellen zum Build-Host syncen, im Docker-Image "thor-build" bauen,
# APK nach dist/ThorConquer.apk holen. Optional: --install (adb install -r auf den Thor).
set -euo pipefail
HOST=${THOR_BUILD_HOST:?set THOR_BUILD_HOST=user@linux-host-with-docker}
REMOTE=/mnt/cache/thor-conquer
REPO="$(cd "$(dirname "$0")/.." && pwd)"

rsync -a --delete --exclude .git --exclude /build --exclude /dist \
    --exclude thor/android/.gradle --exclude thor/android/app/build \
    --exclude thor/android/app/src/main/jniLibs --exclude thor/android/app/src/main/java-sdl \
    "$REPO/" "$HOST:$REMOTE/src/"

ssh "$HOST" "docker run --rm -v $REMOTE:/work thor-build bash /work/src/thor/docker-build.sh"

mkdir -p "$REPO/dist"
scp "$HOST:$REMOTE/out/ThorConquer.apk" "$REPO/dist/ThorConquer.apk"
echo "APK: $REPO/dist/ThorConquer.apk"

if [ "${1:-}" = "--install" ]; then
    : # adb uses ANDROID_SERIAL if set
    adb install -r "$REPO/dist/ThorConquer.apk"
fi
