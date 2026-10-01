package com.webobjects.appserver.websocket;

import java.time.Duration;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.server.ServerUpgradeRequest;
import org.eclipse.jetty.websocket.server.ServerUpgradeResponse;
import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.eclipse.jetty.websocket.server.WebSocketCreator;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.JettyAdaptorProperties;
import com.webobjects.appserver.JettyHandlerDecorator;
import com.webobjects.appserver.WOAdaptorJetty;
import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WORequest;

public class WOJettyWebSocketSupport implements JettyHandlerDecorator {

	/**
	 * ServiceLoader entry point (registered in META-INF/services): wo-adaptor-jetty discovers this and lets us wrap its
	 * handler chain with WebSocket upgrade support. This is what makes WebSockets "on" merely by having this module on the
	 * classpath - no configuration needed.
	 */
	@Override
	public Handler decorate( final Server server, final Handler inner ) {
		return createWebSocketHandler( server, inner );
	}

	private static final Logger logger = LoggerFactory.getLogger( WOJettyWebSocketSupport.class );

	/**
	 * WebSocket idle timeout in seconds, 0 for none. Unset (-1) leaves Jetty's default, so a client that vanishes without a
	 * CLOSE is eventually reaped.
	 */
	private static final int WEBSOCKET_IDLE_TIMEOUT_SECONDS = JettyAdaptorProperties.integer( "JettyWebSocketIdleTimeout", -1 );

	/**
	 * Creates a handler that supports both HTTP and WebSocket requests.
	 * WebSocket upgrade requests are intercepted by the WebSocket infrastructure, other requests go through to the WO handler.
	 *
	 * The handler given is adopted as the returned handler's child, not merely called from it: only then is it part of the
	 * server's component tree, started and stopped with the server. A handler that is called but never started is broken in
	 * ways that depend on what it is - a QoSHandler, for one, assigns its state in doStart() and fails every request without
	 * it.
	 */
	public static Handler createWebSocketHandler( final Server server, final Handler otherHandler ) {

		// Get the WebSocket container from the server
		final ServerWebSocketContainer container = ServerWebSocketContainer.ensure( server );

		if( WEBSOCKET_IDLE_TIMEOUT_SECONDS >= 0 ) {
			container.setIdleTimeout( Duration.ofSeconds( WEBSOCKET_IDLE_TIMEOUT_SECONDS ) );
		}

		logger.info( "WebSocket idle timeout is {} (0 = infinite)", container.getIdleTimeout() );

		// Create an upgrade handler that intercepts WebSocket upgrade requests
		final WebSocketUpgradeHandler upgradeHandler = new WebSocketUpgradeHandler( container ) {

			@Override
			public boolean handle( Request request, Response response, Callback callback ) throws Exception {
				// Check if this is a WebSocket upgrade request for a registered path
				final String path = request.getHttpURI().getPath();

				if( WOWebSocketRegistry.hasHandlerForPath( path ) && isWebSocketUpgrade( request ) ) {
					// Let the WebSocket infrastructure handle the upgrade
					logger.debug( "WebSocket upgrade request for path: {}", path );

					// Create a handler instance for this connection
					final WOWebSocketHandler handler = WOWebSocketRegistry.createHandlerInstance( path, WOApplication.application() );

					if( handler != null ) {
						// Convert the Jetty request to a WORequest so we can pass it to the handler
						final WORequest woRequest = WOAdaptorJetty.WOJettyHandler.requestToWORequest( request );

						// Create the WebSocket creator that returns our listener
						final WebSocketCreator creator = new WebSocketCreator() {
							@Override
							public Object createWebSocket( ServerUpgradeRequest req, ServerUpgradeResponse resp, Callback cb ) {
								return new WOJettyWebSocketListener( handler, woRequest );
							}
						};

						// Perform the WebSocket upgrade
						if( container.upgrade( creator, request, response, callback ) ) {
							return true;
						}
					}
				}

				// Not a WebSocket upgrade, handle like any other HTTP request
				return getHandler().handle( request, response, callback );
			}
		};

		upgradeHandler.setHandler( otherHandler );
		return upgradeHandler;
	}

	private static boolean isWebSocketUpgrade( Request request ) {
		final String upgrade = request.getHeaders().get( "Upgrade" );
		final String connection = request.getHeaders().get( "Connection" );
		return "websocket".equalsIgnoreCase( upgrade ) && connection != null && connection.toLowerCase().contains( "upgrade" );
	}
}