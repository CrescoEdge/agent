#!/bin/bash
#
# Fetch the Cresco component bundles and stage them into src/main/resources under
# VERSION-LESS names (logger.jar, core.jar, ...). HostApplication/StaticPluginLoader
# reference those fixed names, so a version bump never touches this script or any
# runtime Java — the version is read from the pom below.
set -e

VER=$(mvn -q -DforceStdout help:evaluate -Dexpression=project.version 2>/dev/null || true)
[ -z "$VER" ] && VER="1.3-SNAPSHOT"
REPO="https://central.sonatype.com/repository/maven-snapshots/"
RES="src/main/resources"

echo "prebuild: staging io.cresco:*:$VER bundles (version-less) into $RES"
for c in logger core repo sysinfo library controller wsapi stunnel; do
  rm -f "$RES/$c.jar" "$RES/$c"-*-SNAPSHOT.jar
  mvn org.apache.maven.plugins:maven-dependency-plugin:2.1:get \
      -Dtransitive=false -DrepoUrl="$REPO" -Dartifact="io.cresco:$c:$VER"
  cp "$HOME/.m2/repository/io/cresco/$c/$VER/$c-$VER.jar" "$RES/$c.jar"
done
