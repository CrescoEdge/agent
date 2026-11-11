@echo off
REM The embedded Cresco bundles are committed in src\main\resources and pinned by bundles.lock: nothing is downloaded
REM at build time, and "mvn package" verifies the jars against the lock (EmbeddedBundleCheck). To change a bundle, run
REM prebuild.sh (--update or --lock) under bash (Git Bash, WSL): it downloads the published snapshot with curl, checks it
REM against Maven Central's checksum and rewrites bundles.lock.
echo prebuild: the embedded bundles are pinned by bundles.lock; "mvn package" verifies them. Nothing to download.
