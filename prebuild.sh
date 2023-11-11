#!/bin/bash

rm src/main/resources/logger-1.1-SNAPSHOT.jar
rm src/main/resources/core-1.1-SNAPSHOT.jar
rm src/main/resources/library-1.1-SNAPSHOT.jar
rm src/main/resources/controller-1.1-SNAPSHOT.jar
rm src/main/resources/repo-1.1-SNAPSHOT.jar
rm src/main/resources/sysinfo-1.1-SNAPSHOT.jar
#rm src/main/resources/dashboard-1.1-SNAPSHOT.jar
rm src/main/resources/wsapi-1.1-SNAPSHOT.jar

mvn org.apache.maven.plugins:maven-dependency-plugin:get -Dtransitive=false -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:logger:1.1-SNAPSHOT
mvn org.apache.maven.plugins:maven-dependency-plugin:get -Dtransitive=false -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:core:1.1-SNAPSHOT
mvn org.apache.maven.plugins:maven-dependency-plugin:get -Dtransitive=false -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:library:1.1-SNAPSHOT
mvn org.apache.maven.plugins:maven-dependency-plugin:get -Dtransitive=false -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:controller:1.1-SNAPSHOT
mvn org.apache.maven.plugins:maven-dependency-plugin:get -Dtransitive=false -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:repo:1.1-SNAPSHOT
mvn org.apache.maven.plugins:maven-dependency-plugin:get -Dtransitive=false -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:sysinfo:1.1-SNAPSHOT
#mvn org.apache.maven.plugins:maven-dependency-plugin:get -Dtransitive=false -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:dashboard:1.1-SNAPSHOT
mvn org.apache.maven.plugins:maven-dependency-plugin:get -Dtransitive=false -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:wsapi:1.1-SNAPSHOT

cp ~/.m2/repository/io/cresco/logger/1.1-SNAPSHOT/logger-1.1-SNAPSHOT.jar src/main/resources/
cp ~/.m2/repository/io/cresco/core/1.1-SNAPSHOT/core-1.1-SNAPSHOT.jar src/main/resources/
cp ~/.m2/repository/io/cresco/library/1.1-SNAPSHOT/library-1.1-SNAPSHOT.jar src/main/resources/
cp ~/.m2/repository/io/cresco/controller/1.1-SNAPSHOT/controller-1.1-SNAPSHOT.jar src/main/resources/
cp ~/.m2/repository/io/cresco/repo/1.1-SNAPSHOT/repo-1.1-SNAPSHOT.jar src/main/resources/
cp ~/.m2/repository/io/cresco/sysinfo/1.1-SNAPSHOT/sysinfo-1.1-SNAPSHOT.jar src/main/resources/
#cp ~/.m2/repository/io/cresco/dashboard/1.1-SNAPSHOT/dashboard-1.1-SNAPSHOT.jar src/main/resources/
cp ~/.m2/repository/io/cresco/wsapi/1.1-SNAPSHOT/wsapi-1.1-SNAPSHOT.jar src/main/resources/