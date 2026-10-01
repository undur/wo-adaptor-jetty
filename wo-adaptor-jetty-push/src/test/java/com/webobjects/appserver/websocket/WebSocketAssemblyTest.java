package com.webobjects.appserver.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.junit.jupiter.api.Test;

import com.webobjects.appserver.TestApplication;
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
			assertEquals( 200, server.http().get( "/plain" ).status() );
			assertEquals( "GET /plain", server.http().get( "/plain" ).bodyString() );
		}
	}
}
