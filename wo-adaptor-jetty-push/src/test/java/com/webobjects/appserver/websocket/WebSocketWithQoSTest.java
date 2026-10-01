package com.webobjects.appserver.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.handler.QoSHandler;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;

import org.junit.jupiter.api.Test;

import com.webobjects.appserver.TestApplication;
import com.webobjects.appserver.TestServer;

/**
 * The push module's WebSocket support combined with a concurrency limit.
 *
 * The upgrade handler used to call the rest of the chain - here, the QoS handler - without adopting it as a child, so the
 * QoS handler was never started and every request failed with a 500 (#10).
 */
public class WebSocketWithQoSTest {

	static {
		System.setProperty( "JettyMaxConcurrentRequests", "10" );
	}

	@Test
	public void requestsSucceedWithAConcurrencyLimitSet() {
		TestApplication.respondWith( TestApplication::echo );

		try( TestServer server = TestServer.start()) {
			assertInstanceOf( WebSocketUpgradeHandler.class, server.outermostHandler() );
			assertInstanceOf( QoSHandler.class, ((Handler.Wrapper)server.outermostHandler()).getHandler() );
			assertEquals( 200, server.http().get( "/plain" ).status() );
		}
	}
}
