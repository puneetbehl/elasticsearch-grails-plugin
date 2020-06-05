#!/bin/bash

set -e

rm -rf *.zip

./gradlew clean check assemble

filename=$(find build/libs -name "*.jar" | head -1)
filename=$(basename "$filename")

VERSION=$(grep -e "projectVersion" gradle.properties | sed 's/projectVersion=//')

echo "branch: $TRAVIS_BRANCH"
echo "pullrequest: $TRAVIS_PULL_REQUEST"
echo "travis tag: $TRAVIS_TAG"
echo "version: $VERSION"

EXIT_STATUS=0
if [[ -n $TRAVIS_TAG ]] || [[ $TRAVIS_BRANCH == 'master' && $TRAVIS_PULL_REQUEST == 'false' ]] && [[ ! -z $(echo $VERSION | grep "SNAPSHOT") ]]; then

  echo "Publishing archives for branch $TRAVIS_BRANCH"
  if [[ -n $TRAVIS_TAG ]]; then
      ./gradlew bintrayUpload || EXIT_STATUS=$?
  else
      ./gradlew publish || EXIT_STATUS=$?
  fi

  ./publish-docs.sh
fi

exit $EXIT_STATUS