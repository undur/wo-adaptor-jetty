package com.webobjects.appserver;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ServiceLoader;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.QoSHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver._private.WOInputStreamData;
import com.webobjects.appserver._private.WONoCopyPushbackInputStream;
import com.webobjects.appserver._private.WOProperties;
import com.webobjects.foundation.NSArray;
import com.webobjects.foundation.NSData;
import com.webobjects.foundation.NSDictionary;
import com.webobjects.foundation.NSForwardException;
import com.webobjects.foundation.NSProperties;

/**
 * A WOAdaptor based on Jetty.
 *
 * To use, set the property -WOAdaptor WOJettyAdaptor
 */

public class WOAdaptorJetty extends WOAdaptor {

	private static final Logger logger = LoggerFactory.getLogger( WOAdaptorJetty.class );

	/**
	 * userInfo key marking a WOResponse as "unhandled": WO had no answer for the request (typically a route miss) and the
	 * request should fall through to the next handler in the Jetty chain - for instance an ng-objects handler serving
	 * alongside WO in the same server.
	 */
	public static final String UNHANDLED_RESPONSE_KEY = "wo-unhandled-response";

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
				_port = discoverPort( _server );
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
	 * When no WOPort was given (port 0), Jetty picks a free port and we have to find out which one it chose, since WO uses
	 * the port to construct direct-connect URLs. Only network connectors have a local port, so we take the first one of
	 * those. With a server supplied via JettyServerProvider there may be none - no connectors at all, or only non-network
	 * ones (in-memory, unix domain sockets, ...) - in which case there is nothing to discover and WO would silently be left
	 * believing its port is 0. That is not a state this adaptor knows how to serve from, so we fail at startup instead.
	 */
	private static int discoverPort( final Server server ) {
		for( final Connector connector : server.getConnectors() ) {
			if( connector instanceof NetworkConnector networkConnector ) {
				return networkConnector.getLocalPort();
			}
		}

		throw new IllegalStateException( "WOPort is not set (port 0) and the Jetty server has no network connector to discover the port from. Either set WOPort, or make sure the server provided by JettyServerProvider has a network connector" );
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

		// NOTE: We deliberately do NOT call threadPool.setMaxThreads(...) here.
		//
		// When a virtualThreadsExecutor is set on a QueuedThreadPool, the QTP's own *platform* threads remain the I/O
		// carriers/producers (accept + select + the EatWhatYouKill produce loop); actual request handling is offloaded to
		// virtual threads spawned by newVirtualThreadPerTaskExecutor(). So maxThreads would bound the carrier/producer
		// threads, NOT the number of concurrent request-handling virtual threads. A previous setMaxThreads(200) here looked
		// like it limited concurrency but did not (request handling stayed unbounded) and could starve the I/O producers
		// under high connection counts. Real concurrency backpressure is provided by the QoSHandler below instead.
		threadPool.setVirtualThreadsExecutor( java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor() );
		Server server = new Server( threadPool );

		final HttpConfiguration config = new HttpConfiguration();
		config.setSendServerVersion( false ); // Not sending the server software/version is good practice for security

		final HttpConnectionFactory connectionFactory = new HttpConnectionFactory( config );

		final ServerConnector connector = new ServerConnector( server, connectionFactory );
		connector.setPort( port );
		connector.setIdleTimeout( CONNECTOR_IDLE_TIMEOUT_SECONDS * 1000L );
		connector.setAcceptQueueSize( LISTEN_QUEUE_SIZE );
		// connector.setHost( null ); // FIXME: WOHost? // Hugi 2025-11-15
		server.addConnector( connector );

		// The actual WO request handler...
		Handler handler = new WOJettyHandler();

		// ...optionally fronted by a QoSHandler that bounds how many requests are dispatched into WO concurrently.
		// Without this, every incoming request spawns a virtual thread that calls straight into dispatchRequest(), so
		// concurrency is unbounded - a traffic spike (or a slow downstream) can exhaust the DB connection pool, heap, etc.
		// The QoSHandler admits up to JettyMaxConcurrentRequests at a time and *queues* the rest (rather than failing them),
		// which is exactly the backpressure we want. Queued requests are suspended, not blocked on a thread, so this plays
		// nicely with virtual threads.
		handler = withQoSBackpressure( handler );

		// Finally, let optional modules wrap the chain. Decorators sit OUTSIDE (in front of) the QoSHandler on purpose: a
		// WebSocket upgrade, for example, opens a long-lived connection rather than a request/response unit of work, so it
		// must not consume a QoS permit (it would hold it for the lifetime of the socket and never give it back).
		handler = applyHandlerDecorators( server, handler );

		server.setHandler( handler );

		return server;
	}

	/**
	 * Property: how long (seconds) an idle connection is kept open before Jetty closes it. Default 600 (10 minutes).
	 *
	 * The default is deliberately GENEROUS - far above Jetty's own 30-second default - because a fronting
	 * proxy/adaptor (Apache mod_proxy, the classic WO Apache adaptor) POOLS connections to the app and reuses
	 * them after arbitrary idle gaps. The classic WO adaptor in particular was designed against apps whose
	 * adaptor-facing connections never closed. With a short idle timeout, every quiet period leaves the
	 * front-end's pool full of connections we have FIN'd; the next request to reuse one fails or stalls -
	 * experienced as "a user's first request after a quiet spell hangs, sometimes". Size this ABOVE the
	 * front-end's connection reuse ttl (or its idle limit); set it lower only for apps exposed directly to
	 * the internet (where lingering idle connections are a resource-exhaustion concern).
	 */
	private static final int CONNECTOR_IDLE_TIMEOUT_SECONDS = connectorIdleTimeoutSeconds();

	private static int connectorIdleTimeoutSeconds() {
		final int configured = NSProperties.integerForKey( "JettyConnectorIdleTimeoutSeconds" );
		return configured > 0 ? configured : 600;
	}

	/**
	 * Property: the listen socket's accept queue (backlog) size, honoring WO's classic WOListenQueueSize property —
	 * JavaMonitor's "Listen Queue Size" setting reaches the actual socket. Left unconfigured, Jetty's acceptQueueSize
	 * default of 0 makes the JDK fall back to ITS default of 50, which overflows easily under connection bursts (a
	 * crawler fleet, a proxy opening connections in parallel). Overflow on loopback connections fails with an instant
	 * "connection refused" rather than a SYN retry, so a front-end on the same host sees the instance as briefly dead.
	 * Default 511 (the conventional just-below-somaxconn value) when the property is unset.
	 */
	private static final int LISTEN_QUEUE_SIZE = listenQueueSize();

	private static int listenQueueSize() {
		final int configured = NSProperties.integerForKey( "WOListenQueueSize" );
		return configured > 0 ? configured : 511;
	}

	/**
	 * Property: maximum number of requests dispatched into WO concurrently. 0 (the default) means unlimited - i.e. no
	 * backpressure, preserving the historical behaviour unless an app opts in. Recommended sizing is roughly your DB
	 * connection pool size / downstream capacity; too low throttles throughput, too high defeats the purpose.
	 */
	private static final int MAX_CONCURRENT_REQUESTS = NSProperties.integerForKey( "JettyMaxConcurrentRequests" );

	/**
	 * Property: maximum number of requests allowed to wait in the QoS queue once the concurrency limit is reached. Beyond
	 * this, excess requests are rejected (503) rather than queued, bounding memory under overload. 0 (default) = unlimited.
	 */
	private static final int MAX_SUSPENDED_REQUESTS = NSProperties.integerForKey( "JettyMaxSuspendedRequests" );

	/**
	 * Property: how long (seconds) a request may wait in the QoS queue before timing out. 0 (default) = no timeout.
	 */
	private static final int MAX_SUSPEND_SECONDS = NSProperties.integerForKey( "JettyMaxSuspendSeconds" );

	/**
	 * Wrap the given handler in a QoSHandler if (and only if) a concurrency limit has been configured. When
	 * JettyMaxConcurrentRequests is 0 we return the handler untouched, so there's zero added overhead for apps that
	 * haven't opted in to backpressure.
	 */
	private static Handler withQoSBackpressure( final Handler handler ) {

		if( MAX_CONCURRENT_REQUESTS <= 0 ) {
			logger.info( "JettyMaxConcurrentRequests not set; request concurrency into WO is unbounded (no backpressure)" );
			return handler;
		}

		final QoSHandler qos = new QoSHandler( handler );
		qos.setMaxRequestCount( MAX_CONCURRENT_REQUESTS );

		if( MAX_SUSPENDED_REQUESTS > 0 ) {
			qos.setMaxSuspendedRequestCount( MAX_SUSPENDED_REQUESTS );
		}

		if( MAX_SUSPEND_SECONDS > 0 ) {
			qos.setMaxSuspend( java.time.Duration.ofSeconds( MAX_SUSPEND_SECONDS ) );
		}

		logger.info( "QoS backpressure enabled: maxConcurrentRequests={}, maxSuspendedRequests={}, maxSuspendSeconds={}", MAX_CONCURRENT_REQUESTS, MAX_SUSPENDED_REQUESTS, MAX_SUSPEND_SECONDS );

		return qos;
	}

	/**
	 * Apply every JettyHandlerDecorator found on the classpath (via ServiceLoader) around the given handler. Decorators form
	 * the outermost layer of the chain, so they see each request before the QoS handler and WO do. This is how optional
	 * modules plug themselves in: wo-adaptor-jetty-websocket, for instance, contributes the WebSocket upgrade handler this
	 * way, so merely having it on the classpath enables WebSockets - there is no flag to flip.
	 */
	private static Handler applyHandlerDecorators( final Server server, Handler handler ) {

		// Load through this class's own loader rather than the thread context loader: decorator jars live on the same
		// classpath as the adaptor, and the context loader is not guaranteed to be anything meaningful at server-build time
		for( final JettyHandlerDecorator decorator : ServiceLoader.load( JettyHandlerDecorator.class, WOAdaptorJetty.class.getClassLoader() ) ) {
			logger.info( "Applying handler decorator {}", decorator.getClass().getName() );
			handler = decorator.decorate( server, handler );
		}

		return handler;
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

			// WO declined this request: discard its response and let the next handler in the chain have a go (see UNHANDLED_RESPONSE_KEY)
			if( woResponse.userInfoForKey( UNHANDLED_RESPONSE_KEY ) != null ) {
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

				// Request bodies larger than 2 GB are not supported. This is a limit of WO itself, not of this adaptor: the stream
				// classes a WORequest is built on (WONoCopyPushbackInputStream, WOInputStreamData) and the multipart parser all
				// measure content in ints, and WO's own classic adaptor has the same ceiling. We fail clearly rather than silently
				// truncate, and will look into it if the need ever arises.
				if( length > Integer.MAX_VALUE ) {
					throw new IllegalArgumentException( "Request content length %s exceeds the size of an int. Unfortunately, we currently can't handle that".formatted( length ) );
				}

				// All of this stream wrapping is required for WO to be happy. Yay!
				//
				// We deliberately do NOT wrap in a BufferedInputStream here. The buffering was historically load-bearing, so
				// this was confirmed safe by round-tripping real multipart uploads of many sizes (including ones that straddle
				// chunk boundaries and payloads peppered with boundary-like byte sequences) and verifying byte-for-byte
				// integrity. The reasons it holds:
				//
				//  - Jetty's Request.asInputStream() returns CHUNK-SIZED reads: a single read() yields at most one chunk's
				//    worth of bytes, so short reads are normal. It never returns 0 from a blocking read - only >0 or -1 (EOF).
				//    That "never 0" property is what makes dropping the buffer safe.
				//  - WONoCopyPushbackInputStream passes a single underlying read straight through (it does not loop to fill),
				//    so those short reads reach WO's multipart parser unchanged. That's fine, because:
				//  - WO's multipart body reader tolerates short reads: when a boundary-separator match runs off the end of the
				//    current read it reads more to disambiguate and unreads on a false match. And the part data is read in
				//    blocks (4KB), not byte-by-byte, so there's no pathological per-byte path on uploads.
				//
				// A BufferedInputStream would therefore only add an 8KB heap buffer + an arraycopy-per-read on every request
				// carrying a body, coalescing chunks WO is already happy to receive piecemeal - cost with no correctness gain.
				//
				// If a malformed multipart upload (one MISSING the boundary from its Content-Type header) ever misbehaves, note
				// that WO has a separate boundary-recovery path that assumes a small read fills its buffer; that path is fragile
				// to short reads regardless of this change, and well-formed uploads never hit it. // Hugi/Claude 2026-06-16
				final InputStream jettyStream = Request.asInputStream( jettyRequest );
				final WONoCopyPushbackInputStream wrappedStream = new WONoCopyPushbackInputStream( jettyStream, (int)length );
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

			// Single pass over the fields, accumulating ALL values for a given header name.
			//
			// A request can legitimately carry the same header name more than once (e.g. multiple Cookie lines). The
			// obvious `map.put( name, List.of( value ) )` OVERWRITES on the second occurrence, silently dropping all but the
			// last value. So on a repeat we merge into a growable list instead. The common case (every name unique) still
			// costs just one List.of() per field, so this stays cheap.
			//
			// Note: we deliberately use HttpField.getValue() (the raw single value), NOT getValueList(), because the latter
			// splits a single header's value on commas before WO sees it, corrupting values that legitimately contain commas
			// (cookies, dates, etc). See commit cef4670.
			for( final HttpField httpField : jettyRequest.getHeaders() ) {
				final String name = httpField.getName();
				final String value = httpField.getValue();

				final List<String> existing = map.get( name );

				if( existing == null ) {
					map.put( name, List.of( value ) );
				}
				else {
					// Second (or later) occurrence of this name: replace the immutable singleton with a growable list
					final List<String> merged = new ArrayList<>( existing );
					merged.add( value );
					map.put( name, merged );
				}
			}

			return map;
		}
	}
}