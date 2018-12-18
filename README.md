# AWI NearRealTime Series SOS Proxy

Implementation of the [Series REST API](https://github.com/52North/series-rest-api) as a 
proxy in front of the [AWI NearRealTime SOS](https://github.com/52North/awi-nearrealtime-sos).

## Configuration

Series API specific settings can be configured in [`src/main/resources/application.properties`](https://github.com/52North/awi-nearrealtime-series-proxy/blob/master/src/main/resources/application.properties)
and `<webapp>/classes/application.properties`. Especially the `external.url` and `series.database.*` properties should be set here.
The proxy uses a Postgres database for caching service metadata and uses these settings for the connection.

The SOS service(s) that should be harvested are configued in [`src/main/resources/config-data-sources.json`](https://github.com/52North/awi-nearrealtime-series-proxy/blob/master/src/main/resources/config-data-sources.json)
and `<webapp>/classes/config-data-sources.json` respectively.

Logging is done using [Logback](https://logback.qos.ch/) and can be configured 
in [`src/main/resources/logback.xml`](https://github.com/52North/awi-nearrealtime-series-proxy/blob/master/src/main/resources/logback.xml) 
and `<webapp>/classes/logback.xml` respectively.

## Building

The service requires Java 8 and [Maven](https://maven.apache.org/):

```
mvn clean install
```

The WAR file can be found at `target/de.awi.sos.api.war`


## Deployment

The WAR file can be deployed in a Java Application Server of your choice. Please adjust the configuration files (especially `hibernate.properties` either prior to building or in the WAR file.


### Docker

There is a [`Dockerfile`](https://github.com/52North/awi-nearrealtime-sos/blob/master/Dockerfile) that creates a [Jetty](https://www.eclipse.org/jetty/) deployment:

```sh
docker build -t awi/nearrealtime-series-proxy:latest .
```

```sh
docker run -it -p 8080:8080 \
  -v ./logback.xml:/var/lib/jetty/webapps/ROOT/WEB-INF/classes/logback.xml:ro
  -v ./config-data-sources.json:/var/lib/jetty/webapps/ROOT/WEB-INF/classes/config-data-sources.json:ro
  -v ./application.properties:/var/lib/jetty/webapps/ROOT/WEB-INF/classes/application.properties:ro
  awi/nearrealtime-series-proxy:latest
```
Be aware that you have to link the database to the container or have both containers on the same Docker network.

After this the SOS should be accessible at http://localhost:8080/api/

A `docker-compose` example deployment can be found [here](https://github.com/52North/awi-nearrealtime).
