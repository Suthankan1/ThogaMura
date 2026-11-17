# Java backend

Parent Maven reactor: `backend/pom.xml`.

- `protocol`: shared wire constants/DTOs only.
- `network-server`: Spring Boot host for real hand-written JDK TCP/NIO/TLS socket services, plus Java URL/HTTP supplier availability connector and JDBC/PostgreSQL persistence.
- `api-gateway`: Spring MVC HTTP/SSE to/from hand-written JDK socket clients; **no database library**.

Java target: 21. Spring Boot: 3.5.7. Commands:

```bash
mvn -f backend/pom.xml clean verify
mvn -f backend/pom.xml package
java -jar backend/network-server/target/network-server-0.1.0-SNAPSHOT.jar
java -jar backend/api-gateway/target/api-gateway-0.1.0-SNAPSHOT.jar
```

Run the last two commands in separate terminals, after starting local PostgreSQL and setting DB environment variables for the network server. At this scaffold stage, the network server has **no actual socket listeners**; the API gateway exposes only `/api/v1/meta` and Actuator. See the contracts and owner work packages before adding features.
