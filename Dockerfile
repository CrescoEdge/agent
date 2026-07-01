FROM eclipse-temurin:21-jre-jammy
EXPOSE 8080 8181 8282 32005 32010
# Version-less copy so the image build survives version bumps (matches the
# version-less runtime bundle naming). The agent build emits exactly one jar.
COPY target/agent-*.jar /opt/cresco/agent.jar
WORKDIR /opt/cresco
CMD ["java","-Xmx1024M","-Dcresco_service_key=12345","-Droot_log_level=INFO","-Dregionname=global-region","-Dagentname=global-controller","-Dis_global=true","-Ddiscovery_secret_global=sec","-Ddiscovery_secret_region=sec","-Ddiscovery_secret_agent=sec","-jar","agent.jar"]
