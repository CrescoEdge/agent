
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:logger:1.1-SNAPSHOT
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:core:1.1-SNAPSHOT
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:library:1.1-SNAPSHOT
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:controller:1.1-SNAPSHOT
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:repo:1.1-SNAPSHOT
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:sysinfo:1.1-SNAPSHOT
CALL mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:dashboard:1.1-SNAPSHOT

copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\logger\1.1-SNAPSHOT\logger-1.1-SNAPSHOT.jar src\main\resources\
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\core\1.1-SNAPSHOT\core-1.1-SNAPSHOT.jar src\main\resources\
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\library\1.1-SNAPSHOT\library-1.1-SNAPSHOT.jar src\main\resources\
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\controller\1.1-SNAPSHOT\controller-1.1-SNAPSHOT.jar src\main\resources\
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\repo\1.1-SNAPSHOT\repo-1.1-SNAPSHOT.jar src\main\resources\
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\sysinfo\1.1-SNAPSHOT\sysinfo-1.1-SNAPSHOT.jar src\main\resources\
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\dashboard\1.1-SNAPSHOT\dashboard-1.1-SNAPSHOT.jar src\main\resources\