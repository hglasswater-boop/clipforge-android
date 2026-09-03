#!/usr/bin/env sh
set -eu

# Keep Android versionCode monotonic across independent workflows.
# The same commit always gets the same build number.
COMMIT_EPOCH_SECONDS=$(git show -s --format=%ct HEAD)
BUILD_NUMBER=$((COMMIT_EPOCH_SECONDS / 60))

if [ "$BUILD_NUMBER" -lt 1000000 ] || [ "$BUILD_NUMBER" -gt 2100000000 ]; then
  echo "Invalid Android build number: $BUILD_NUMBER" >&2
  exit 1
fi

printf '%s\n' "$BUILD_NUMBER"
