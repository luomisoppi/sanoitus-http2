# Sanoitus HTTP/2

## Overview

Sanoitus HTTP/2 enables HTTP/2 communication in programs executed with [Sanoitus](https://github.com/luomisoppi/sanoitus) engine. It contains DSLs and their interpreters for both server and client functionality.

The details of the DSLs are specified in corresponding source code files as Scaladoc:

* Server DSL: [Http2ServerLanguage](./http2-server/src/main/scala/sanoitus/http2/server/Http2ServerLanguage.scala)
* Client DSL: [Http2ClientLanguage](./http2-client/src/main/scala/sanoitus/http2/client/Http2ClientLanguage.scala)

Additionally, runnable examples are found in the ['example'](./example) directory as a stand-alone project - see the example project [README](./example/README.md) for details.

## Current status

Passes [h2spec](https://github.com/summerwind/h2spec) [v2.6.0](https://github.com/summerwind/h2spec/releases/tag/v2.6.0) (testable using the [file server](./example/src/main/scala/sanoitus/http2/example/FileServer.scala) example).

Missing features:

* Server push. The server DSL does not contain operations for pushing. The client will ignore incoming server pushes.
* Prioritization. The client DSL does not contain operations for setting priorities. The server will ignore incoming priority information.
* Outbound padding. Both server and client will send all the paddable frames without any padding.
* Trailers. The DSLs do not contain operations to send or receive trailing headers. The interpreters ignore incoming trailers.
