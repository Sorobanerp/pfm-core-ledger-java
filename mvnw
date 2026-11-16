#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.
set -e

if [ -z "${JAVA_HOME}" ] || [ ! -x "${JAVA_HOME}/bin/java" ]; then
  if command -v java >/dev/null 2>&1; then
    JAVA_BIN="$(readlink -f "$(command -v java)")"
    export JAVA_HOME="$(dirname "$(dirname "${JAVA_BIN}")")"
  fi
fi

MAVEN_VERSION="3.9.9"
MAVEN_HOME_DIR="$HOME/.m2/wrapper/dists/apache-maven-${MAVEN_VERSION}"

if [ ! -x "${MAVEN_HOME_DIR}/bin/mvn" ]; then
  mkdir -p "${MAVEN_HOME_DIR}"
  echo "Downloading Apache Maven ${MAVEN_VERSION}..." >&2
  curl -fsSL "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/${MAVEN_VERSION}/apache-maven-${MAVEN_VERSION}-bin.tar.gz" \
    | tar -xz -C "${MAVEN_HOME_DIR}" --strip-components=1
fi

exec "${MAVEN_HOME_DIR}/bin/mvn" "$@"
