package com.webobjects.appserver;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver._private.WOInputStreamData;
import com.webobjects.appserver._private.WONoCopyPushbackInputStream;
import com.webobjects.appserver._private.WOProperties;
import com.webobjects.appserver.websocket.WOJettyWebSocketSupport;
import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSData;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSForwardException;

/**
 * A WOAdaptor based on Jetty.
 *
 * To use, set the property -WOAdaptor WOJettyAdaptor
 */

public class WOAdaptorJetty extends WOAdaptor {

	private static final Logger logger = LoggerFactory.getLogger( WOAdaptorJetty.class );

	/**
	 * Flip this to turn websockets on/off
	 *
	 *  FIXME: This should be configurable in a nicer way // Hugi 2025-11-13
	 */
	private static final boolean ENABLE_WEBSOCKETS = true;

	/**
	 * The Jetty server instance
	 */
	private Server _server;

	/**
	 * Invoked by WO to construct an adaptor instance
	 */
	public WOAdaptorJetty( String name, NSDictionary<String, Object> config ) throws UnknownHostException {
		super( name, config );
		_port = port( config );

		checkPortAvailable( _port );
	}

	/**
	 * Overridden, since WO will invoke this method when constructing a direct connect URL
	 */
	@Override
	public int port() {
		return _port;
	}

	/**
	 * @return The port we'll be listening on. 0 (zero) if no port set, meaning Jetty will pick a random port
	 */
	private static int port( NSDictionary<String, Object> config ) {
		int port = 0;

		final Number number = (Number)config.objectForKey( WOProperties._PortKey );

		if( number != null ) {
			port = number.intValue();
		}

		if( port < 0 ) {
			port = 0;
		}

		return port;
	}

	/**
	 * Briefly try binding to the requested port. If unsuccessful, emulate WO's behaviour (wrap the BindException in NSForwardException) to help ERXApplication catch it and stop any apps occupying the port
	 */
	private static void checkPortAvailable( final int port ) {

		// Port 0 just means "WOPort not set", so we don't need to perform a check (Jetty will pick a random free port)
		if( port != 0 ) {
			try( ServerSocket socket = new ServerSocket( port )) {}
			catch( IOException e ) {
				throw new NSForwardException( e );
			}
		}
	}

	@Override
	public boolean dispatchesRequestsConcurrently() {
		return true;
	}

	@Override
	public void unregisterForEvents() {
		logger.info( "Stopping %s".formatted( getClass().getSimpleName() ) );

		try {
			_server.stop();
		}
		catch( Exception e ) {
			// Wrapping in RuntimeException always feels a little dirty, but I think it's nicer than no handling at all
			throw new RuntimeException( "Error stopping server", e );
		}
	}

	@Override
	public void registerForEvents() {

		_server = createJettyServer();

		try {
			logger.info( "%s starting %s".formatted( getClass().getSimpleName(), _port == 0 ? "on a random port" : "on port " + _port ) );

			_server.start();

			if( _port == 0 ) {
				// FIXME: We probably need to check if the connector is a ServerConnector. Or if the server has any connectors at all // Hugi 2025-11-16
				final ServerConnector connector = (ServerConnector)_server.getConnectors()[0];
				_port = connector.getLocalPort();
				WOApplication.application().setPort( _port );
				logger.info( "Running on port %s".formatted( _port ) );
			}

			// FIXME: WOHost? // Hugi 2025-11-15
			// WOApplication.application()._setHost( InetAddress.getLocalHost().getHostName() );
		}
		catch( final Exception e ) {
			e.printStackTrace();
			System.exit( -1 );
		}
	}

	/**
	 * Interface that can be implemented by the Application class to create the actual Jetty server instance
	 */
	public interface JettyServerProvider {
		public Server createJettyServer( int port );
	}

	/**
	 * @return The Jetty Server instance that we'll use to serve requests
	 */
	public Server createJettyServer() {
		if( WOApplication.application() instanceof JettyServerProvider jsp ) {
			return jsp.createJettyServer( _port );
		}

		return createDefaultJettyServer( _port );
	}

	/**
	 * @return Our default way of constructing a server, if the user doesn't provide his own
	 */
	private static Server createDefaultJettyServer( int port ) {
		final QueuedThreadPool threadPool = new QueuedThreadPool();
		threadPool.setVirtualThreadsExecutor( java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor() );
		Server server = new Server( threadPool );

		final HttpConfiguration config = new HttpConfiguration();
		config.setSendServerVersion( false ); // Not sending the server software/version is good practice for security

		final HttpConnectionFactory connectionFactory = new HttpConnectionFactory( config );

		final ServerConnector connector = new ServerConnector( server, connectionFactory );
		connector.setPort( port );
		// connector.setHost( null ); // FIXME: WOHost? // Hugi 2025-11-15
		server.addConnector( connector );

		Handler handler = new WOJettyHandler();

		// If websockets are enabled, we wrap the handler with WS upgrade capabilities
		if( ENABLE_WEBSOCKETS ) {
			handler = WOJettyWebSocketSupport.createWebSocketHandler( server, handler );
		}

		server.setHandler( handler );

		return server;
	}

	public static class WOJettyHandler extends Handler.Abstract {

		@Override
		public boolean handle( Request request, Response response, Callback callback ) throws Exception {
			return doRequest( request, response, callback );
		}

		private boolean doRequest( final Request jettyRequest, final Response jettyResponse, Callback callback ) throws IOException {

			final WORequest woRequest = requestToWORequest( jettyRequest );

			// This is where the application logic will perform it's actual work
			final WOResponse woResponse = WOApplication.application().dispatchRequest( woRequest );

			// FIXME: Experimental functionality for passing control through to JEtty
			if( woResponse.userInfoForKey( "wo-unhandled-response" ) != null ) {
				return false;
			}

			jettyResponse.setStatus( woResponse.status() );

			for( final Entry<String, NSArray<String>> entry : woResponse.headers().entrySet() ) {
				final String headerName = entry.getKey();
				final NSArray<String> headerValues = entry.getValue();

				// Note: You'd think you could always copy headers using the following logic, adding all the header values at the same time:
				// 		jettyResponse.getHeaders().add( headerName, headerValues );
				// However, using this method, Jetty will construct a single header and put all the values into a comma separated list.
				// This is fine for most headers - but it breaks the set-cookie header since each cookie must get it's own set-cookie header.
				// https://datatracker.ietf.org/doc/html/rfc6265#section-3
				// For this reason, we add the set-cookie header one value at a time, each in it's own separate header
				if( "set-cookie".equals( headerName ) ) {
					for( final String headerValue : headerValues ) {
						jettyResponse.getHeaders().add( headerName, headerValue );
					}
				}
				else {
					jettyResponse.getHeaders().add( headerName, headerValues );
				}
			}

			if( woResponse.contentInputStream() != null ) {
				final long contentLength = woResponse.contentInputStreamLength(); // If an InputStream is present, the stream's length must be present as well

				if( contentLength == -1 ) {
					throw new IllegalArgumentException( "WOResponse.contentInputStream() is set but contentInputLength has not been set. You must provide the content length when serving an InputStream" );
				}

				jettyResponse.getHeaders().put( "content-length", String.valueOf( contentLength ) );

				// Content.Source.from() handles buffering internally via ByteBufferPool
				// No need to wrap in BufferedInputStream (would cause double-buffering)
				final Content.Source cs = Content.Source.from( woResponse.contentInputStream() );
				Content.copy( cs, jettyResponse, callback );
			}
			else {
				final NSData responseContent = woResponse.content();

				jettyResponse.getHeaders().put( "content-length", String.valueOf( responseContent.length() ) );

				try( final OutputStream out = Response.asBufferedOutputStream( jettyRequest, jettyResponse )) {
					responseContent.writeToStream( out );
				}

				callback.succeeded();
			}

			return true;
		}

		/**
		 * @return the given Request converted to a WORequest
		 */
		public static WORequest requestToWORequest( final Request jettyRequest ) {

			final ConnectionMetaData meta = jettyRequest.getConnectionMetaData();

			final String method = jettyRequest.getMethod();
			final String uri = jettyRequest.getHttpURI().getPathQuery();
			final String httpVersion = meta.getHttpVersion().asString();
			final Map<String, List<String>> headers = headerMapFromJettyRequest( jettyRequest );

			final NSData contentData;

			final long length = jettyRequest.getLength();

			if( length > 0 ) {

				// FIXME: Missing support for larger request bodies (limitations imposed by WONoCopyPushbackInputStream and WOInputStreamData) // Hugi 2025-11-15
				if( length > Integer.MAX_VALUE ) {
					throw new IllegalArgumentException( "Request content length %s exceeds the size of an int. Unfortunately, we currently can't handle that".formatted( length ) );
				}

				// All of this stream wrapping is required for WO to be happy. Yay!
				final InputStream jettyStream = Request.asInputStream( jettyRequest );
				final InputStream bufferedStream = new BufferedInputStream( jettyStream );
				final WONoCopyPushbackInputStream wrappedStream = new WONoCopyPushbackInputStream( bufferedStream, (int)length );
				contentData = new WOInputStreamData( wrappedStream, (int)length );
			}
			else {
				contentData = NSData.EmptyData;
			}

			final WORequest worequest = WOApplication.application().createRequest( method, uri, httpVersion, headers, contentData, null );

			populateAddresses( meta, worequest );

			return worequest;
		}

		/**
		 * Populate origin data in the WORequest
		 */
		private static void populateAddresses( final ConnectionMetaData meta, final WORequest worequest ) {

			if( meta.getRemoteSocketAddress() instanceof InetSocketAddress remote ) {
				worequest._setOriginatingAddress( remote.getAddress() );
				worequest._setOriginatingPort( remote.getPort() );
			}

			if( meta.getLocalSocketAddress() instanceof InetSocketAddress local ) {
				worequest._setAcceptingAddress( local.getAddress() );
				worequest._setAcceptingPort( local.getPort() );
			}
		}

		/**
		 * @return The headers from the Request as a Map
		 */
		private static Map<String, List<String>> headerMapFromJettyRequest( final Request jettyRequest ) {
			final Map<String, List<String>> map = new HashMap<>();

			for( final HttpField httpField : jettyRequest.getHeaders() ) {
				map.put( httpField.getName(), List.of( httpField.getValue() ) );
			}

			return map;
		}
	}
}