package com.webobjects.appserver;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.Socket;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.QoSHandler;
import org.junit.jupiter.api.Test;

import com.webobjects.appserver._private.WOProperties;
import com.webobjects.foundation.NSDictionary;

/**
 * The adaptor's default server, with no configuration
 */
public class ServerAssemblyTest {

	@Test
	public void withoutAConcurrencyLimitThereIsNoQoSHandler() {
		try( TestServer server = TestServer.start()) {
			assertInstanceOf( WOAdaptorJetty.WOJettyHandler.class, server.outermostHandler() );
			assertFalse( server.outermostHandler() instanceof QoSHandler );
		}
	}

	/**
	 * WO stops an adaptor whose startup failed before its server was created; that is not a second failure
	 */
	@Test
	public void stoppingAnAdaptorThatNeverStartedIsHarmless() throws Exception {
		TestApplication.instance();
		final WOAdaptorJetty adaptor = new WOAdaptorJetty( "never-started", new NSDictionary<String, Object>( Integer.valueOf( 0 ), WOProperties._PortKey ) );
		assertDoesNotThrow( adaptor::unregisterForEvents );
	}

	/**
	 * Port 0 means "pick one", and the adaptor has to find out which one Jetty picked
	 */
	@Test
	public void portZeroIsReplacedWithTheBoundPort() throws Exception {
		try( TestServer server = TestServer.start()) {
			assertTrue( server.port() > 0 );

			try( Socket socket = new Socket( "localhost", server.port() )) {
				assertTrue( socket.isConnected() );
			}
		}
	}

	/**
	 * A server with nowhere to discover the port from fails clearly, rather than leaving WO believing its port is 0
	 */
	@Test
	public void serverWithoutANetworkConnectorFailsClearly() throws Exception {
		final Method discoverPort = WOAdaptorJetty.class.getDeclaredMethod( "discoverPort", Server.class );
		discoverPort.setAccessible( true );

		final InvocationTargetException thrown = assertThrows( InvocationTargetException.class, () -> discoverPort.invoke( null, new Server() ) );
		assertInstanceOf( IllegalStateException.class, thrown.getCause() );
		assertTrue( thrown.getCause().getMessage().contains( "network connector" ) );
	}
}
