#!/bin/sh
# Bump the version, build a release signed with the CrossPoint key, and publish it to android.crosspointreader.com.
# Usage: scripts/release.sh          minor bump (1.2.3 -> 1.3.0)
#        scripts/release.sh patch    patch bump (1.2.3 -> 1.2.4)
# The key is ~/.android/crosspoint-release.jks; its password is in the macOS Keychain.
# On success the VERSION bump is committed and tagged (v1.3.0).
set -e
cd "$(dirname "$0")/.."

# SHA-256 of the release certificate. Installed apps only accept updates signed with this key.
RELEASE_CERT=d9ff2dbd42db7c2350d5ff14f124d0f68be3e1561d94a707a442b7b29d40a921

[ -z "$(git status --porcelain)" ] || [ "$ALLOW_DIRTY" = 1 ] || {
  echo "Working tree has uncommitted changes. Commit them, or run with ALLOW_DIRTY=1."; exit 1; }

OLD=$(cat VERSION)
IFS=. read -r MAJOR MINOR PATCH <<EOF
$OLD
EOF
case "${1:-minor}" in
  patch) VERSION_NAME="$MAJOR.$MINOR.$((PATCH + 1))" ;;
  minor) VERSION_NAME="$MAJOR.$((MINOR + 1)).0" ;;
  *) echo "Usage: $0 [patch]"; exit 1 ;;
esac
VERSION_CODE=$(( $(date +%s) / 60 ))   # minutes since epoch: always increases, fits Android's limit

echo "$VERSION_NAME" > VERSION
trap 'echo "$OLD" > VERSION; echo "Release failed; VERSION restored to $OLD."' EXIT

export CROSSPOINT_KEYSTORE="${CROSSPOINT_KEYSTORE:-$HOME/.android/crosspoint-release.jks}"
CROSSPOINT_KEYSTORE_PASSWORD=$(security find-generic-password -a crosspoint-release -s crosspoint-release-keystore -w)
export CROSSPOINT_KEYSTORE_PASSWORD

./gradlew -q :app:assembleRelease -PversionCode="$VERSION_CODE"
APK=app/build/outputs/apk/release/app-release.apk

SDK=${ANDROID_HOME:-$HOME/Library/Android/sdk}
APKSIGNER=$(ls -d "$SDK"/build-tools/* | sort -V | tail -1)/apksigner
"$APKSIGNER" verify --print-certs "$APK" | grep -qi "SHA-256 digest: $RELEASE_CERT" || {
  echo "Release APK is not signed with the CrossPoint release key. Not publishing."; exit 1; }

OUT=update-service/public
rm -f "$OUT"/*.apk
cp "$APK" "$OUT/crosspoint-$VERSION_CODE.apk"
cp "$APK" "$OUT/crosspoint.apk"   # stable "always latest" link to share: /crosspoint.apk
SHA=$(shasum -a 256 "$APK" | cut -d' ' -f1)
cat > "$OUT/latest.json" <<EOF
{"versionCode": $VERSION_CODE, "versionName": "$VERSION_NAME", "apk": "crosspoint-$VERSION_CODE.apk", "sha256": "$SHA"}
EOF

if [ -n "$DRY_RUN" ]; then
  (cd update-service && npx -y wrangler deploy --dry-run)
  trap - EXIT; echo "$OLD" > VERSION
  echo "[dry run] Would publish $VERSION_NAME ($VERSION_CODE)."
  exit 0
fi

(cd update-service && npx -y wrangler deploy)
trap - EXIT
git add VERSION && git commit -q -m "Release $VERSION_NAME" -- VERSION
git tag "v$VERSION_NAME"
echo "Published $VERSION_NAME ($VERSION_CODE): https://android.crosspointreader.com/latest.json"
