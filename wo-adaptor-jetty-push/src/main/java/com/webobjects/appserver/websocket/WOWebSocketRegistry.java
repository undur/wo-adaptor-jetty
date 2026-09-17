package com.webobjects.appserver.websocket;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WOApplication;

/**
 * Registry for WebSocket endpoints in a WebObjects application.
 * Use this to register WebSocket handlers for specific URL paths.
 *
 * Example usage in your Application class:
 *
 * <pre>
 * public void finishInitialization() {
 *     super.finishInitialization();
 *     WOWebSocketRegistry.register("/ws/chat", ChatWebSocketHandler.class);
 *     WOWebSocketRegistry.register("/ws/notifications", NotificationWebSocketHandler.class);
 * }
 * </pre>
 */

public class WOWebSocketRegistry {

	private static final Logger logger = LoggerFactory.getLogger( WOWebSocketRegistry.class );

	/**
	 * Map of path patterns to handler classes
	 */
	private static final Map<String, Class<? extends WOWebSocketHandler>> handlers = new ConcurrentHashMap<>();

	/**
	 * Register a WebSocket handler for a specific path.
	 *
	 * @param path the URL path (e.g., "/ws/chat")
	 * @param handlerClass the handler class to instantiate for connections to this path
	 */
	public static void register( String path, Class<? extends WOWebSocketHandler> handlerClass ) {

		if( path == null || path.isEmpty() ) {
			throw new IllegalArgumentException( "WebSocket path cannot be null or empty" );
		}

		if( handlerClass == null ) {
			throw new IllegalArgumentException( "Handler class cannot be null" );
		}

		// Normalize path to start with /
		final String normalizedPath = path.startsWith( "/" ) ? path : "/" + path;

		logger.info( "Registering WebSocket handler {} for path {}", handlerClass.getSimpleName(), normalizedPath );
		handlers.put( normalizedPath, handlerClass );
	}

	/**
	 * Unregister a WebSocket handler for a specific path.
	 *
	 * @param path the URL path to unregister
	 */
	public static void unregister( String path ) {
		String normalizedPath = path.startsWith( "/" ) ? path : "/" + path;
		Class<? extends WOWebSocketHandler> removed = handlers.remove( normalizedPath );
		if( removed != null ) {
			logger.info( "Unregistered WebSocket handler for path {}", normalizedPath );
		}
	}

	/**
	 * Get the handler class for a specific path.
	 *
	 * @param path the URL path
	 * @return the handler class, or null if no handler is registered for this path
	 */
	public static Class<? extends WOWebSocketHandler> handlerForPath( String path ) {
		return handlers.get( path );
	}

	/**
	 * @return true if a handler is registered for the given path
	 */
	public static boolean hasHandlerForPath( String path ) {
		return handlers.containsKey( path );
	}

	/**
	 * @return the number of registered WebSocket endpoints
	 */
	public static int registeredEndpointCount() {
		return handlers.size();
	}

	/**
	 * Create a new instance of the handler for the given path.
	 *
	 * @param path the URL path
	 * @param application the WOApplication instance to inject into the handler
	 * @return a new handler instance, or null if no handler is registered
	 */
	public static WOWebSocketHandler createHandlerInstance( String path, WOApplication application ) {
		Class<? extends WOWebSocketHandler> handlerClass = handlerForPath( path );
		if( handlerClass == null ) {
			return null;
		}

		try {
			WOWebSocketHandler handler = handlerClass.getDeclaredConstructor().newInstance();
			handler._setApplication( application );
			return handler;
		}
		catch( Exception e ) {
			logger.error( "Failed to instantiate WebSocket handler {} for path {}", handlerClass.getSimpleName(), path, e );
			return null;
		}
	}
}