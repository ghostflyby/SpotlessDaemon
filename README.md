# SpotlessDaemon

Gradle plugin exposing a long-running HTTP daemon to format code using [Spotless](https://github.com/diffplug/spotless).

Designed as a companion for integrating spotless into IDEs with higher throughput.

## Getting Started

### Gradle

In your buildscript, apply the plugin:

```kotlin
plugins {
    id("com.diffplug.spotless")
    id("dev.ghostflyby.spotless.daemon")
}
```

**Don't forget to apply the `com.diffplug.spotless` plugin**

Specify address with gradle properties and start the daemon:

```shell
./gradlew spotlessDaemon -Pdev.ghostflyby.spotless.daemon.port=8080
./gradlew spotlessDaemon -Pdev.ghostflyby.spotless.daemon.unixsocket=/path/to/socket
```

The task will block, running the daemon until interrupted.

### HTTP API

#### GET /

* `200 OK` as a health check

#### POST /?path={path}

* `404 Not Found` if file not covered by spotless, either no such file or not included in config.
* `400 Bad Request` if `path` parameter is missing
* `200 OK` with formatted file content as `text/plain` in body if successful
* `200 OK` with empty body if no changes were made
* `500 Internal Server Error` if problems occurred during formatting

#### POST /stop

* `200 OK` and stops the daemon