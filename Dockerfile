FROM openjdk:jre-alpine
EXPOSE 8181 32005 32010
RUN apk update
RUN apk add eudev
COPY target/agent-1.0-SNAPSHOT.jar /opt/cresco/agent-1.0-SNAPSHOT.jar
WORKDIR /opt/cresco
CMD ["java","-Xmx1024M","-jar","-Dis_global=true","agent-1.0-SNAPSHOT.jar"]
