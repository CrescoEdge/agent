FROM eclipse-temurin:21-jre-jammy
EXPOSE 8080 8181 8282 32005 32010
# Version-less copy so the image build survives version bumps (matches the
# version-less runtime bundle naming). The agent build emits exactly one jar.
COPY target/agent-*.jar /opt/cresco/agent.jar
WORKDIR /opt/cresco
# No secrets in the image: the service key and discovery secrets used to be baked in here (12345 / sec), the same
# in every container and visible in `ps`. Pass them at run time, e.g.
#   docker run -e CRESCO_cresco_service_key=... -e CRESCO_discovery_secret_global=... \
#              -e CRESCO_discovery_secret_region=... -e CRESCO_discovery_secret_agent=... <image>
# or mount owner-only files and name them with CRESCO_<param>_file.
CMD ["java","-Xmx1024M","-Droot_log_level=INFO","-Dregionname=global-region","-Dagentname=global-controller","-Dis_global=true","-jar","agent.jar"]
