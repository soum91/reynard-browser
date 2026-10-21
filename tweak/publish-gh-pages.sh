#!/usr/bin/env bash
# Force-pushes ./public as the gh-pages branch. Prints git's own error as a workflow annotation on failure.
set -uo pipefail
cd "$(dirname "$0")/../public"
touch .nojekyll
rm -rf .git
git init -q -b gh-pages
git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git add -A
git commit -qm "Publish ${GITHUB_SHA:-local} (run ${GITHUB_RUN_NUMBER:-0})"
git remote add origin "https://x-access-token:${GH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git"
if ! OUT=$(git push -f origin gh-pages 2>&1); then
  echo "::error::gh-pages push failed: $(echo "$OUT" | tr '\n' ' ' | cut -c1-500)"
  exit 1
fi
echo "Published to gh-pages"
