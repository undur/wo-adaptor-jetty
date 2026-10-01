package com.webobjects.appserver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A response marked with {@link WOAdaptorJetty#UNHANDLED_RESPONSE_KEY} is discarded, and the request goes to the next
 * handler in the chain.
 *
 * The adaptor's default server has no next handler, so this assembles the chain an application serving both WO and
 * another framework would use: the WO handler first, then a fallback.
 */
public class UnhandledResponseTest {

	private static Server server;
	private static int port;

	@BeforeAll
	public static void start() throws Exception {
		TestApplication.instance();
		server = new Server();
		final ServerConnector connector = new ServerConnector( server );
		connector.setPort( 0 );
		server.addConnector( connector );

		final Handler fallback = new Handler.Abstract() {
			@Override
			public boolean handle( final Request request, final Response response, final Callback callback ) {
				response.write( true, ByteBuffer.wrap( "from the fallback".getBytes( StandardCharsets.UTF_8 ) ), callback );
				return true;
			}
		};

		server.setHandler( new Handler.Sequence( new WOAdaptorJetty.WOJettyHandler(), fallback ) );
		server.start();
		port = connector.getLocalPort();
	}

	@AfterAll
	public static void stop() throws Exception {
		server.stop();
	}

	@Test
	public void markedResponseFallsThroughToTheNextHandler() {
		TestApplication.respondWith( request -> {
			final WOResponse response = TestApplication.text( "from WO" );
			response.setStatus( 404 );
			response.setUserInfoForKey( "true", WOAdaptorJetty.UNHANDLED_RESPONSE_KEY );
			return response;
		} );

		final RawHttp.Response response = new RawHttp( port ).get( "/elsewhere" );
		assertEquals( 200, response.status() );
		assertEquals( "from the fallback", response.bodyString() );
	}

	@Test
	public void unmarkedResponseIsServedByWO() {
		TestApplication.respondWith( request -> TestApplication.text( "from WO" ) );

		final RawHttp.Response response = new RawHttp( port ).get( "/mine" );
		assertEquals( 200, response.status() );
		assertEquals( "from WO", response.bodyString() );
	}
}
