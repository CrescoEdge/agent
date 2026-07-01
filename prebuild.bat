
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://central.sonatype.com/repository/maven-snapshots/ -Dartifact=io.cresco:logger:1.3-SNAPSHOT
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://central.sonatype.com/repository/maven-snapshots/ -Dartifact=io.cresco:core:1.3-SNAPSHOT
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://central.sonatype.com/repository/maven-snapshots/ -Dartifact=io.cresco:library:1.3-SNAPSHOT
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://central.sonatype.com/repository/maven-snapshots/ -Dartifact=io.cresco:controller:1.3-SNAPSHOT
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://central.sonatype.com/repository/maven-snapshots/ -Dartifact=io.cresco:repo:1.3-SNAPSHOT
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://central.sonatype.com/repository/maven-snapshots/ -Dartifact=io.cresco:sysinfo:1.3-SNAPSHOT
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://central.sonatype.com/repository/maven-snapshots/ -Dartifact=io.cresco:wsapi:1.3-SNAPSHOT
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://central.sonatype.com/repository/maven-snapshots/ -Dartifact=io.cresco:stunnel:1.3-SNAPSHOT

REM Stage under VERSION-LESS names so runtime Java never carries the version.
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\logger\1.3-SNAPSHOT\logger-1.3-SNAPSHOT.jar src\main\resources\logger.jar
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\core\1.3-SNAPSHOT\core-1.3-SNAPSHOT.jar src\main\resources\core.jar
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\library\1.3-SNAPSHOT\library-1.3-SNAPSHOT.jar src\main\resources\library.jar
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\controller\1.3-SNAPSHOT\controller-1.3-SNAPSHOT.jar src\main\resources\controller.jar
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\repo\1.3-SNAPSHOT\repo-1.3-SNAPSHOT.jar src\main\resources\repo.jar
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\sysinfo\1.3-SNAPSHOT\sysinfo-1.3-SNAPSHOT.jar src\main\resources\sysinfo.jar
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\wsapi\1.3-SNAPSHOT\wsapi-1.3-SNAPSHOT.jar src\main\resources\wsapi.jar
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\stunnel\1.3-SNAPSHOT\stunnel-1.3-SNAPSHOT.jar src\main\resources\stunnel.jar
