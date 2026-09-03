## 🔌 wo-adaptor-jetty

A Jetty-based WOAdaptor. Based on Jetty's own servlet-free APIs making it's relatively simple and lightweigt.

## Usage

Build/install and add as a dependency to your app. Then pass the launch argument `-WOAdaptor WOAdaptorJetty` to your application.

```xml
<dependency>
	<groupId>is.rebbi</groupId>
	<artifactId>wo-adaptor-jetty</artifactId>
	<version>0.0.1-SNAPSHOT</version>
</dependency>
```

### WebSockets (experimental)

WebSocket support lives in a separate module. Adding it to your classpath is all it takes: the adaptor discovers it automatically and enables WebSocket upgrades in its default server, with nothing to configure. The two modules are released together and must be used at the same version.

```xml
<dependency>
	<groupId>is.rebbi</groupId>
	<artifactId>wo-adaptor-jetty-websocket</artifactId>
	<version>0.0.1-SNAPSHOT</version>
</dependency>
```

Register endpoints from your `Application` class with `WOWebSocketRegistry.register( "/ws/chat", ChatHandler.class )`. If you build your own Jetty server through `JettyServerProvider`, wrap your handler with `WOJettyWebSocketSupport.createWebSocketHandler( server, handler )` yourself, since the automatic discovery only applies to the default server.

## Why?

* WebSockets.
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
    stopHeartbeat(session); // Cleanup (done automatically, but good practice)
}
```

**Configuration:**
- Default WebSocket idle timeout: **0 seconds (infinite)**
- Set via property: `-DJettyWebSocketIdleTimeout=300` (5 minutes)
- Heartbeat interval should be less than idle timeout

**Recommended setup for production:**
- Idle timeout: 300 seconds (5 minutes)
- Heartbeat interval: 120 seconds (2 minutes)

### Example: Echo Server

See `com.webobjects.appserver.websocket.examples.EchoWebSocketHandler` for a complete working example with heartbeat.
-->