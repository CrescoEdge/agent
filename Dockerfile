FROM openjdk:8-slim-bullseye
EXPOSE 8080 8181 8282 32005 32010
COPY target/agent-1.2-SNAPSHOT.jar /opt/cresco/agent-1.2-SNAPSHOT.jar
WORKDIR /opt/cresco
CMD ["java","-Xmx1024M","-Dcresco_service_key=12345","-Droot_log_level=INFO","-Dregionname=global-region","-Dagentname=global-controller","-Dis_global=true","-Ddiscovery_secret_global=sec","-Ddiscovery_secret_region=sec","-Ddiscovery_secret_agent=sec","-jar","agent-1.2-SNAPSHOT.jar"]




