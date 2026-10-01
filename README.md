## 🔌 wo-adaptor-jetty

A Jetty-based WOAdaptor. Based on Jetty's own servlet-free APIs making it's relatively simple and lightweigt.

## Usage

Releases are deployed to the WOCommunity maven repository, so if your environment is set up for WO development just add the dependency to your `pom`. The adaptor selects itself at startup, so no launch argument is needed; pass `-WOAdaptor` explicitly only when you want some other adaptor. Requires JDK 25.

```xml
<dependency>
	<groupId>is.rebbi</groupId>
	<artifactId>wo-adaptor-jetty</artifactId>
	<version>0.12.2</version>
</dependency>
```
## Why?

* WebSockets.
* SSE (Server-sent events).
* I like having control over the HTTP-serving part of my apps.
* The main adaptor for `ng-objects` is Jetty-based so a Jetty-based `WOAdaptor` allows `WO` and `ng` to be served from the same app/Jetty server instance.

## Does it work?

Works great. Since November 2025 I use it in all of my applications, some of which handle quite a bit of traffic, large file uploads, multipart requests etc.

## Performance?

Running `ab` locally on my MacBook M4 to serve a simple ~230kb resource returns the following results, so performance is pretty great. Disabling `KeepAlive` (removing the `-k` parameter) slows us down by roughly half. Which is probably a more realistic benchmark. Not that common for legit clients to make 15.000 requests/sec to your app.

```
# ab -k -n 10000 -c 16 http://127.0.0.1:1200/Apps/WebObjects/Hugi.woa/res/app/ZillaSlab-Light.ttf

Concurrency Level:      16
Time taken for tests:   0.692 seconds
Complete requests:      10000
Failed requests:        0
Keep-Alive requests:    10000
Total transferred:      2399870000 bytes
HTML transferred:       2398360000 bytes
Requests per second:    14460.19 [#/sec] (mean)
Time per request:       1.106 [ms] (mean)
Time per request:       0.069 [ms] (mean, across all concurrent requests)
Transfer rate:          3388922.70 [Kbytes/sec] received
```

## Tests

```
mvn verify                 # the test suite
mvn verify -Pintegration   # also the slower tests that launch a separate JVM against the packaged framework
```

The tests start the adaptor's default server on a free port in front of a real `WOApplication`, and talk raw HTTP to it. Each regression test names the fix it guards. The integration tests cover the adaptor selecting itself at startup, which happens while bundles load and so can only be observed in a freshly launched application.

## Experimental: server push

Server-sent events and WebSockets. Both work and are in use, but their API may still change before it settles, so expect to adjust on upgrade.


Server push lives in a separate module, `wo-adaptor-jetty-push`, released together with the adaptor and used at the same version. Server-sent events arrive with 1.0.0; 0.9.0 shipped the module as `wo-adaptor-jetty-websocket`, with WebSockets only.

```xml
<dependency>
	<groupId>is.rebbi</groupId>
	<artifactId>wo-adaptor-jetty-push</artifactId>
	<version>0.12.2</version>
</dependency>
```

**Server-sent events** need nothing beyond the module: return an `SSEStream`'s response from any action and keep sending to it.

```java
private static final SSEHub TICKER = new SSEHub();

public WOActionResults eventsAction() {
	return TICKER.open().response();          // WO returns at once; the response stays open
}

// later, from anywhere:
TICKER.broadcast( "tick", Instant.now().toString() );
```

`SSEStream` queues events (`send` never blocks), writes a keep-alive comment every 30 seconds while quiet, and closes itself when the client goes away, at which point `onClose` listeners run and an `SSEHub` drops it. The adaptor streams the response with chunked transfer encoding, so this works only with wo-adaptor-jetty, not WO's classic adaptor. If you use `JettyMaxConcurrentRequests`, exclude your event paths with `JettyQoSExcludedPaths` (comma-separated Jetty path specs such as `/sse/*`), since an open response holds its permit for as long as it lives.

**WebSockets** are enabled by the module's presence on the classpath: the adaptor discovers it and adds WebSocket upgrades to its default server. Register endpoints from your `Application` class with `WOWebSocketRegistry.register( "/ws/chat", ChatHandler.class )`. If you build your own Jetty server through `JettyServerProvider`, wrap your handler with `WOJettyWebSocketSupport.createWebSocketHandler( server, handler )` yourself, since the automatic discovery only applies to the default server.

## Changelog

See [CHANGES.md](CHANGES.md).

<!--
## WebSockets

wo-adaptor-jetty includes experimental WebSocket support.

### Quick Start

**1. Create a WebSocket handler:**

```java
import com.webobjects.appserver.websocket.WOWebSocketHandler;
import com.webobjects.appserver.websocket.WOWebSocketSession;
import java.io.IOException;

public class ChatHandler extends WOWebSocketHandler {

    @Override
    public void onConnect(WOWebSocketSession session, WORequest request) {
        logger.info("Client connected: {}", session.getRemoteAddress());

        // Access the initial HTTP request for authentication, session management, etc.
        String sessionId = request.cookieValueForKey("wosid");
        String userId = request.stringFormValueForKey("userId");

        try {
            session.sendText("Welcome to the chat!");
        } catch (IOException e) {
            logger.error("Failed to send welcome", e);
        }
    }

    @Override
    public void onTextMessage(WOWebSocketSession session, String message) {
        logger.info("Received: {}", message);
        // Broadcast to all connected clients, process message, etc.
    }

    @Override
    public void onClose(WOWebSocketSession session, int statusCode, String reason) {
        logger.info("Client disconnected");
    }
}
```

**2. Register the handler in your Application class:**

```java
public Application() {
    WOWebSocketRegistry.register("/ws/chat", ChatHandler.class);
}
```

**3. Connect from JavaScript:**

```javascript
const ws = new WebSocket('ws://localhost:1200/ws/chat');

ws.onopen = () => {
    console.log('Connected!');
    ws.send('Hello from the browser!');
};

ws.onmessage = (event) => {
    console.log('Received:', event.data);
};
```

### WebSocket Handler API

Your handler can override these methods:

- **`onConnect(WOWebSocketSession session, WORequest request)`** - Called when a client connects (includes the initial HTTP request for authentication/cookies/headers)
- **`onTextMessage(WOWebSocketSession session, String message)`** - Text message received
- **`onBinaryMessage(WOWebSocketSession session, ByteBuffer data)`** - Binary data received
- **`onClose(WOWebSocketSession session, int statusCode, String reason)`** - Connection closed
- **`onError(WOWebSocketSession session, Throwable cause)`** - Error occurred

All handlers have access to `application` (the WOApplication instance).

### WOWebSocketSession API

The session object provides:

- **`sendText(String message)`** - Send text to client
- **`sendBinary(ByteBuffer data)`** - Send binary data to client
- **`close()`** - Close the connection
- **`close(int statusCode, String reason)`** - Close with status code
- **`isOpen()`** - Check if connection is open
- **`getRemoteAddress()`** - Get client address
- **`getAttribute(String key)` / `setAttribute(String key, Object value)`** - Store per-session data

### Heartbeat Support

To keep connections alive and detect dead connections, use the built-in heartbeat:

```java
@Override
public void onConnect(WOWebSocketSession session, WORequest request) {
    // Send "ping" every 120 seconds (2 minutes)
    startHeartbeat(session, 120);
}

@Override
public void onTextMessage(WOWebSocketSession session, String message) {
    // Filter out heartbeat messages
    if (isHeartbeatMessage(session, message)) {
        return;
    }
    // Handle actual messages...
}

@Override
public void onClose(WOWebSocketSession session, int statusCode, String reason) {
    stopHeartbeat(session); // Optional: the adaptor stops it after onClose()
}
```

**Configuration:**
- Default WebSocket idle timeout: **Jetty's default, 30 seconds**; `0` means none
- Set via property: `-DJettyWebSocketIdleTimeout=300` (5 minutes)
- Heartbeat interval should be less than idle timeout

**Recommended setup for production:**
- Idle timeout: 300 seconds (5 minutes)
- Heartbeat interval: 120 seconds (2 minutes)

### Example: Echo Server

See `com.webobjects.appserver.websocket.examples.EchoWebSocketHandler` for a complete working example with heartbeat.
-->