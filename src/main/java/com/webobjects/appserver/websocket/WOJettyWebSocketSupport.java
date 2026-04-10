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

import com.webobjects.appserver.WOAdaptorJetty;
import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WORequest;
import com.webobjects.foundation.NSProperties;

public class WOJettyWebSocketSupport {

	private static final Logger logger = LoggerFactory.getLogger( WOJettyWebSocketSupport.class );

	/**
	 * WebSocket idle timeout in seconds Set to 0 for no timeout.
	 */
	private static final int WEBSOCKET_IDLE_TIMEOUT_SECONDS = NSProperties.stringForKey( "JettyWebSocketIdleTimeout" ) != null ? NSProperties.integerForKey( "JettyWebSocketIdleTimeout" ) : 0;

	/**
	 * Creates a handler that supports both HTTP and WebSocket requests.
	 * WebSocket upgrade requests are intercepted by the WebSocket infrastructure, other requests go through to the WO handler.
	 */
	public static Handler createWebSocketHandler( final Server server, final Handler otherHandler ) {

		// Get the WebSocket container from the server
		final ServerWebSocketContainer container = ServerWebSocketContainer.ensure( server );

		container.setIdleTimeout( Duration.ofSeconds( WEBSOCKET_IDLE_TIMEOUT_SECONDS ) );
		logger.info( "WebSocket idle timeout set to {} seconds (0 = infinite)", WEBSOCKET_IDLE_TIMEOUT_SECONDS );

		// Create an upgrade handler that intercepts WebSocket upgrade requests
		return new WebSocketUpgradeHandler( container ) {

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
				return otherHandler.handle( request, response, callback );
			}
		};
	}

	private static boolean isWebSocketUpgrade( Request request ) {
		final String upgrade = request.getHeaders().get( "Upgrade" );
		final String connection = request.getHeaders().get( "Connection" );
		return "websocket".equalsIgnoreCase( upgrade ) && connection != null && connection.toLowerCase().contains( "upgrade" );
	}
}