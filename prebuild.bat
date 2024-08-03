
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:logger:1.2-SNAPSHOT
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:core:1.2-SNAPSHOT
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:library:1.2-SNAPSHOT
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:controller:1.2-SNAPSHOT
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:repo:1.2-SNAPSHOT
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:sysinfo:1.2-SNAPSHOT
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:wsapi:1.2-SNAPSHOT
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:stunnel:1.2-SNAPSHOT

copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\logger\1.2-SNAPSHOT\logger-1.2-SNAPSHOT.jar src\main\resources\
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\core\1.2-SNAPSHOT\core-1.2-SNAPSHOT.jar src\main\resources\
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\library\1.2-SNAPSHOT\library-1.2-SNAPSHOT.jar src\main\resources\
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\controller\1.2-SNAPSHOT\controller-1.2-SNAPSHOT.jar src\main\resources\
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\repo\1.2-SNAPSHOT\repo-1.2-SNAPSHOT.jar src\main\resources\
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\sysinfo\1.2-SNAPSHOT\sysinfo-1.2-SNAPSHOT.jar src\main\resources\
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\wsapi\1.2-SNAPSHOT\wsapi-1.2-SNAPSHOT.jar src\main\resources\
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\stunnel\1.2-SNAPSHOT\stunnel-1.2-SNAPSHOT.jar src\main\resources\