#!/usr/bin/env bash
# Publishes a release: the tag starts the workflow that builds the signed APKs.
set -euo pipefail

VERSION="${1:-}"
if [ -z "$VERSION" ]; then
    echo "usage: scripts/release.sh v0.2.0"
    exit 1
fi

cd "$(dirname "$0")/.."

git status --short
if [ -n "$(git status --short)" ]; then
    echo "There are changes that are not committed yet"
    exit 1
fi

git tag -a "$VERSION" -m "WIFIGram $VERSION"
git push origin "$VERSION"
echo "The release $VERSION is on its way"
