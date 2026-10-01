package com.webobjects.appserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The harness itself works: the adaptor starts on a free port and requests reach the application
 */
public class HarnessSmokeTest {

	@Test
	public void requestReachesTheApplication() {
		try( TestServer server = TestServer.start()) {
			assertTrue( server.port() > 0, "port 0 was replaced with the port Jetty bound" );

			final RawHttp.Response response = server.http().get( "/hello?x=1" );
			assertEquals( 200, response.status() );
			assertEquals( "GET /hello?x=1", response.bodyString() );
		}
	}
}
