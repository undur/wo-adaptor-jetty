package com.webobjects.appserver.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.webobjects.appserver.TestApplication;
import com.webobjects.appserver.TestServer;

/**
 * The push module's WebSocket support combined with a concurrency limit.
 *
 * The upgrade handler wraps the rest of the chain - here, the QoS handler - but does not adopt it as a child, so the QoS
 * handler is never started and every request fails with a 500 (#10). Remove {@code @Disabled} when that is fixed.
 */
public class WebSocketWithQoSTest {

	static {
		System.setProperty( "JettyMaxConcurrentRequests", "10" );
	}

	@Test
	@Disabled( "Fails until #10 is fixed: the WebSocket upgrade handler never starts the chain it wraps" )
	public void requestsSucceedWithAConcurrencyLimitSet() {
		TestApplication.respondWith( TestApplication::echo );

		try( TestServer server = TestServer.start()) {
			assertEquals( 200, server.http().get( "/plain" ).status() );
		}
	}
}
