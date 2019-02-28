#!/usr/bin/env bash
set -euvo pipefail

if [[ ${TRAVIS_TAG} != v* ]]; then
  echo "Not on a git tag"
  exit 1
fi

export VERSION="$(echo "$TRAVIS_TAG" | sed 's@^v@@')"

mkdir -p target
cd target

if [ -d sbt-extras ]; then
  echo "Removing former sbt-extras clone"
  rm -rf sbt-extras
fi

echo "Cloning"
git clone "https://${GH_TOKEN}@github.com/coursier/sbt-extras.git" -q -b master sbt-extras
cd sbt-extras

git config user.name "Travis-CI"
git config user.email "invalid@travis-ci.com"

sed -i.bak 's@default_coursier_launcher_version="[^"]*"@default_coursier_launcher_version="'"$VERSION"'"@' sbt
rm -f sbt.bak

git add -- sbt

MSG="Switch to coursier sbt-launcher $VERSION"

# probably not fine with i18n
if git status | grep "nothing to commit" >/dev/null 2>&1; then
  echo "Nothing changed"
else
  git commit -m "$MSG"

  echo "Pushing changes"
  git push origin master
fi
