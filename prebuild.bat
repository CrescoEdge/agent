
mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:core:1.0-SNAPSHOT
mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:library:1.0-SNAPSHOT
mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:controller:1.0-SNAPSHOT
mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:cep:1.0-SNAPSHOT
mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:repo:1.0-SNAPSHOT
mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:sysinfo:1.0-SNAPSHOT
mvn org.apache.maven.plugins:maven-dependency-plugin:get -DrepoUrl=https://oss.sonatype.org/content/repositories/snapshots -Dartifact=io.cresco:dashboard:1.0-SNAPSHOT

copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\core\1.0-SNAPSHOT\core-1.0-SNAPSHOT.jar src\main\resources\
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\library\1.0-SNAPSHOT\library-1.0-SNAPSHOT.jar src\main\resources\
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\controller\1.0-SNAPSHOT\controller-1.0-SNAPSHOT.jar src\main\resources\
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\cep\1.0-SNAPSHOT\cep-1.0-SNAPSHOT.jar src\main\resources\
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\repo\1.0-SNAPSHOT\repo-1.0-SNAPSHOT.jar src\main\resources\
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\sysinfo\1.0-SNAPSHOT\sysinfo-1.0-SNAPSHOT.jar src\main\resources\
copy /Y %systemdrive%%homepath%\.m2\repository\io\cresco\dashboard\1.0-SNAPSHOT\dashboard-1.0-SNAPSHOT.jar src\main\resources\