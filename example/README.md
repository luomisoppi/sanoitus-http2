# Sanoitus HTTP/2 examples

## Overview

This project contains two examples of using Sanoitus HTTP/2 library:

* File server - browse your local file system through HTTP/2 server. Uses server DSL only.
* Proxy server - browse Wikipedia through local HTTP/2 server. The server multiplexes all the requests it receives to a single outgoing HTTP/2 connection. Uses both server and client DSLs.

## Running the examples

### Preparation

Compile and publish Sanoitus HTTP/2 artifacts to your local Maven repository by running the following command in the root directory of this Git repository clone (parent of the directory this file is in):

* Scala 2.13: ./gradlew publishToMavenLocal
* Scala 2.12: ./gradlew -c settings-2.12.gradle publishToMavenLocal

### File server

* Scala 2.13: ./gradlew fileServer
* Scala 2.12: ./gradlew -c settings-2.12.gradle fileServer

The file server will answer at <https://localhost:8443/>.

### Proxy server

* Scala 2.13: ./gradlew proxyServer
* Scala 2.12: ./gradlew -c settings-2.12.gradle proxyServer

The proxy server will answer at <https://localhost:8443/>.
