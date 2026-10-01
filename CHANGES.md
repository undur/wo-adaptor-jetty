# Changelog

## Unreleased

- **The WebSocket upgrade handler starts the chain it wraps**
  With the push module on the classpath and `JettyMaxConcurrentRequests` set, every request failed with a
  500: the upgrade handler called the rest of the chain without adopting it as a child, so the QoS handler
  inside it was never started. Without a concurrency limit the WebObjects handler ran unstarted, which
  happened to work. The wrapped chain is now the upgrade handler's child, and the `JettyHandlerDecorator`
  contract says a decorator must adopt the handler it wraps. (#10)

- **Each response header value is sent as a field of its own**
  Values of a header an application appended more than once were joined into one comma-separated field,
  which is only correct for headers whose syntax is a list. `WWW-Authenticate` and `Proxy-Authenticate`
  became ambiguous, since a challenge's own parameters contain commas, and any non-list header appended
  twice was mangled. Every value now gets its own field, which is equivalent wherever joining was valid,
  so `Set-Cookie` no longer needs special treatment. (#12)

- **A test suite**
  Tests start the adaptor the way WebObjects does, in front of a real `WOApplication`, and assert on the
  raw HTTP that goes over the wire. Each regression test names the fix it guards, and each was checked by
  reintroducing its bug and seeing the test fail. Covered: request headers and bodies, multipart uploads
  at sizes that straddle Jetty's chunk boundaries, the 411 for chunked bodies, which paths are accepted,
  response headers, unknown-length streaming, the unhandled-response fall-through, QoS and its excluded
  paths, port discovery and server-sent events. Adaptor self-selection, which happens while bundles load,
  has an integration test that launches a separate JVM against the packaged framework:
  `mvn verify -Pintegration`. A test for the WebSocket and QoS combination is disabled until #10 is
  fixed. (#15)

## 2026-09-23 (0.12.1)

- **Request paths may contain characters such as `|`**
  Jetty's default URI compliance rejected them with `400 Illegal Path Character` before the request
  reached WebObjects. The classic adaptor passes such paths through, and URLs in the wild carry them.
  Suspicious characters (`\`, NUL, control characters), encoded slashes and path traversal are still
  rejected.

## 2026-09-19 (0.12.0)

- **The adaptor selects itself**
  The core module is a WO framework whose principal class names the Jetty adaptor at bundle-loading
  time, so an application that depends on it no longer has to pass `-WOAdaptor WOAdaptorJetty`. An
  explicit `-WOAdaptor`, or the property set in a properties file, still wins.

## 2026-09-19 (0.11.0)

- **An event stream commits its response at once**
  `SSEStream` writes an opening comment as soon as it is created, so its headers reach the client
  immediately. Previously nothing was written until the first event or keep-alive, leaving the browser
  in "connecting" while every proxy in between looked at an idle connection it might time out.

- **The default keep-alive interval is 10 seconds, down from 30**
  A WebObjects instance's adaptor configuration carries a `recvTimeout` that JavaMonitor defaults to 30
  seconds, and modulo applies it as the idle timeout of its connection to the instance. A keep-alive
  interval equal to that timeout is a race the timeout can win, and a client answers an aborted stream by
  reconnecting, over and over, on a quiet feed.

## 2026-09-17 (0.10.0)

- **Server-sent events**
  `SSEStream` and `SSEHub` in the push module: return an open-ended response from any action and keep
  sending to it.

- **Responses of unknown length are streamed**
  A response with a content stream and no length is sent with chunked transfer encoding until the stream
  ends, instead of with a `Content-Length` of 0.

- **`JettyQoSExcludedPaths`**
  Path specs that bypass the QoS concurrency limit, for long-lived responses that would otherwise hold a
  permit for their whole lifetime.

- **The WebSocket module is renamed `wo-adaptor-jetty-push`**
  It now holds server push in general. Applications using WebSockets change the artifactId; package names
  are unchanged.

- **Event streams send `Cache-Control: no-store`**
  And `SSEStream` documents the Firefox gotcha of concurrent requests to an identical stream URL.

## 2026-09-15 (0.9.0)

First release. In production use since November 2025; published as a pre-release ahead of 1.0 while the
package name and configuration surface are finalised.

- **Two modules sharing one version**
  `wo-adaptor-jetty`, the adaptor, and `wo-adaptor-jetty-websocket`, experimental WebSocket support
  enabled by its presence on the classpath through the `JettyHandlerDecorator` extension point.

- **Optional request backpressure**
  A Jetty `QoSHandler`, configured with `JettyMaxConcurrentRequests`, `JettyMaxSuspendedRequests` and
  `JettyMaxSuspendSeconds`.

- **Connector idle timeout and accept queue size**
  `JettyConnectorIdleTimeoutSeconds`, default 600, and an accept queue honouring WO's `WOListenQueueSize`,
  default 511.

- **Request bodies without a `Content-Length` are refused**
  Answered with `411 Length Required` instead of being silently treated as empty.

- **Repeated same-name request headers are no longer dropped**

- **Port discovery for `WOPort` 0**
  Uses the first network connector, and fails clearly if there is none.

- **`WOAdaptorJetty.UNHANDLED_RESPONSE_KEY`**
  A documented contract for letting a request fall through to the next Jetty handler.

- **`JettyServerProvider`**
  Lets an application build the Jetty server itself.
