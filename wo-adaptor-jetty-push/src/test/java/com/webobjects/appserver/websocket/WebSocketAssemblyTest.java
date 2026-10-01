package com.webobjects.appserver.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.jetty.server.Handler;

import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.Test;

import com.webobjects.appserver.RawHttp;
import com.webobjects.appserver.TestApplication;
import com.webobjects.appserver.WOAdaptorJetty;
import com.webobjects.appserver.TestServer;

/**
 * With the push module on the classpath, the adaptor discovers its handler decorator and installs WebSocket upgrade
 * support as the outermost handler of the default server
 */
public class WebSocketAssemblyTest {

	@Test
	public void upgradeHandlerIsOutermostAndRequestsStillReachWO() {
		TestApplication.respondWith( TestApplication::echo );

		try( TestServer server = TestServer.start()) {
			assertInstanceOf( WebSocketUpgradeHandler.class, server.outermostHandler() );

			// The WO handler is the upgrade handler's child, and so started with the server rather than merely called (#10)
			final Handler inner = ((Handler.Wrapper)server.outermostHandler()).getHandler();
			assertInstanceOf( WOAdaptorJetty.WOJettyHandler.class, inner );
			assertTrue( inner.isStarted() );

			assertEquals( 200, server.http().get( "/plain" ).status() );
			assertEquals( "GET /plain", server.http().get( "/plain" ).bodyString() );
		}
	}

	/**
	 * An upgrade request for a registered path is answered by the WebSocket infrastructure, with the handshake accept key
	 * computed from the client's key as RFC 6455 prescribes
	 */
	@Test
	public void registeredPathUpgradesToAWebSocket() {
		WOWebSocketRegistry.register( "/ws/echo", com.webobjects.appserver.websocket.examples.EchoWebSocketHandler.class );

		try( TestServer server = TestServer.start(); RawHttp.OpenResponse open = server.http().openRaw( "GET /ws/echo HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n\r\n" )) {
			final String received = open.readUntil( "\r\n\r\n", 5_000 );
			assertTrue( received.startsWith( "HTTP/1.1 101" ), received );
			assertTrue( received.contains( "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=" ), received );
		}
	}
}