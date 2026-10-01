package com.webobjects.appserver;

import java.lang.reflect.Field;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;

import com.webobjects.appserver._private.WOProperties;
import com.webobjects.foundation.NSDictionary;

/**
 * The adaptor, started the way WebObjects starts it: constructed with an adaptor configuration and registered for
 * events. With port 0 Jetty picks a free port, which is how the tests avoid each other - and which exercises the
 * adaptor's port discovery on every run.
 *
 * Whether the adaptor builds its default server or an application-provided one, and how that server is assembled
 * (QoS handler, decorators such as the push module's WebSocket support), depends on the classpath and on the configuration
 * the adaptor read when it was loaded. Test classes that need a particular configuration set it as system properties in a
 * static initializer, before anything touches WOAdaptorJetty; surefire gives every test class a JVM of its own.
 */
public class TestServer implements AutoCloseable {

	private final WOAdaptorJetty _adaptor;

	private TestServer() {
		TestApplication.instance();
		final NSDictionary<String, Object> config = new NSDictionary<>( Integer.valueOf( 0 ), WOProperties._PortKey );

		try {
			_adaptor = new WOAdaptorJetty( "test", config );
		}
		catch( final Exception e ) {
			throw new RuntimeException( e );
		}

		_adaptor.registerForEvents();
	}

	/**
	 * Start the adaptor on a free port
	 */
	public static TestServer start() {
		return new TestServer();
	}

	/**
	 * @return The port the adaptor ended up listening on
	 */
	public int port() {
		return _adaptor.port();
	}

	/**
	 * @return The Jetty server the adaptor built, for tests that check how it was assembled
	 */
	public Server jettyServer() {
		try {
			final Field field = WOAdaptorJetty.class.getDeclaredField( "_server" );
			field.setAccessible( true );
			return (Server)field.get( _adaptor );
		}
		catch( final ReflectiveOperationException e ) {
			throw new RuntimeException( e );
		}
	}

	/**
	 * @return The outermost handler of the server's handler chain
	 */
	public Handler outermostHandler() {
		return jettyServer().getHandler();
	}

	/**
	 * @return A raw HTTP client for this server
	 */
	public RawHttp http() {
		return new RawHttp( port() );
	}

	@Override
	public void close() {
		_adaptor.unregisterForEvents();
	}
}
