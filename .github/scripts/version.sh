#!/usr/bin/env bash
# Reads versionName and versionCode from app/build.gradle and fails when changelog.json has no
# entry for that versionName (the in-app updater shows it). In GitHub Actions the values become
# step outputs "version" and "code".
set -euo pipefail

version=$(sed -n "s/^ *versionName '\(.*\)'.*/\1/p" app/build.gradle)
code=$(sed -n 's/^ *versionCode \([0-9][0-9]*\).*/\1/p' app/build.gradle)
if [ -z "$version" ] || [ -z "$code" ]; then
  echo "::error file=app/build.gradle::versionName or versionCode not found"
  exit 1
fi
if ! jq -e --arg v "$version" '.versions[] | select(.version == $v and (.changes | length) > 0)' \
    changelog.json > /dev/null; then
  echo "::error file=changelog.json::No entry for version $version: add it on top of changelog.json (see AGENTS.md)"
  exit 1
fi
echo "Version $version (code $code), changelog entry found"
if [ -n "${GITHUB_OUTPUT:-}" ]; then
  echo "version=$version" >> "$GITHUB_OUTPUT"
  echo "code=$code" >> "$GITHUB_OUTPUT"
fi
