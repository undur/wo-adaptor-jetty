package com.webobjects.appserver.websocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WOApplication;
import com.webobjects.appserver.WORequest;

/**
 * Base class for WebSocket handlers in WebObjects applications.
 * Subclass this to handle WebSocket connections at specific endpoints.
 *
 * Example:
 * <pre>
 * public class ChatHandler extends WOWebSocketHandler {
 *     {@literal @}Override
 *     public void onConnect(WOWebSocketSession session) {
 *         logger.info("Client connected: {}", session.getRemoteAddress());
 *     }
 *
 *     {@literal @}Override
 *     public void onTextMessage(WOWebSocketSession session, String message) {
 *         // Echo the message back
 *         try {
 *             session.sendText("Echo: " + message);
 *         } catch (IOException e) {
 *             logger.error("Failed to send message", e);
 *         }
 *     }
 * }
 * </pre>
 */
public abstract class WOWebSocketHandler {

	/**
	 * Logger for this handler. Subclasses can use this for logging.
	 */
	private final Logger logger = LoggerFactory.getLogger( getClass() );

	/**
	 * The WOApplication instance that this handler is associated with
	 */
	private WOApplication _application;

	/**
	 * Called when a new WebSocket connection is established.
	 * Override this to perform initialization when a client connects.
	 *
	 * @param session the WebSocket session for this connection
	 * @param request the initial HTTP request that initiated the WebSocket upgrade
	 */
	public void onConnect( WOWebSocketSession session, WORequest request ) {}

	/**
	 * Called when a text message is received from the client.
	 *
	 * @param session the WebSocket session
	 * @param message the text message received
	 */
	public void onTextMessage( WOWebSocketSession session, String message ) {}

	/**
	 * Called when a binary message is received from the client.
	 *
	 * @param session the WebSocket session
	 * @param data the binary data received
	 */
	public void onBinaryMessage( WOWebSocketSession session, ByteBuffer data ) {}

	/**
	 * Called when the WebSocket connection is closed.
	 *
	 * @param session the WebSocket session
	 * @param statusCode the close status code
	 * @param reason the reason for closing
	 */
	public void onClose( WOWebSocketSession session, int statusCode, String reason ) {}

	/**
	 * Called when an error occurs on the WebSocket connection.
	 *
	 * @param session the WebSocket session
	 * @param cause the error that occurred
	 */
	public void onError( WOWebSocketSession session, Throwable cause ) {
		logger.error( "WebSocket error", cause );
	}

	/**
	 * Set the WOApplication instance. Called internally by the adaptor.
	 *
	 * @param application the application instance
	 */
	public final void _setApplication( WOApplication application ) {
		this._application = application;
	}

	/**
	 * @return the WOApplication instance
	 */
	public final WOApplication application() {
		return _application;
	}

	// ========== Heartbeat Support ==========

	private static final String HEARTBEAT_EXECUTOR_KEY = "_heartbeat_executor";
	private static final String HEARTBEAT_FUTURE_KEY = "_heartbeat_future";

	/**
	 * Start a heartbeat that sends periodic ping messages to keep the connection alive.
	 * The heartbeat will automatically stop when the connection closes.
	 *
	 * @param session the WebSocket session
	 * @param intervalSeconds interval between heartbeat messages in seconds
	 */
	protected void startHeartbeat( WOWebSocketSession session, int intervalSeconds ) {
		startHeartbeat( session, intervalSeconds, "ping" );
	}

	/**
	 * Start a heartbeat that sends periodic messages to keep the connection alive.
	 * The heartbeat will automatically stop when the connection closes.
	 *
	 * @param session the WebSocket session
	 * @param intervalSeconds interval between heartbeat messages in seconds
	 * @param message the message to send (default: "ping")
	 */
	protected void startHeartbeat( WOWebSocketSession session, int intervalSeconds, String message ) {

		// Create a single-threaded executor for this session's heartbeat
		final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor( r -> {
			Thread t = new Thread( r );
			t.setDaemon( true ); // Daemon thread won't prevent JVM shutdown
			t.setName( "WebSocket-Heartbeat-" + session.getRemoteAddress() );
			return t;
		} );

		// Schedule the heartbeat
		final ScheduledFuture<?> future = executor.scheduleAtFixedRate( () -> {
			if( session.isOpen() ) {
				try {
					session.sendText( message );
					logger.debug( "Sent heartbeat to {}", session.getRemoteAddress() );
				}
				catch( IOException e ) {
					logger.warn( "Heartbeat failed for {}, connection likely dead", session.getRemoteAddress() );
					stopHeartbeat( session );
				}
			}
			else {
				logger.debug( "Session closed, stopping heartbeat for {}", session.getRemoteAddress() );
				stopHeartbeat( session );
			}
		}, intervalSeconds, intervalSeconds, TimeUnit.SECONDS );

		// Store executor and future so we can clean up later
		session.setAttribute( HEARTBEAT_EXECUTOR_KEY, executor );
		session.setAttribute( HEARTBEAT_FUTURE_KEY, future );

		logger.info( "Started heartbeat for {} (interval: {}s, message: '{}')", session.getRemoteAddress(), intervalSeconds, message );
	}

	/**
	 * Stop the heartbeat for a session.
	 * Automatically invoked when the connection closes (but can be called manually if required)
	 */
	protected void stopHeartbeat( WOWebSocketSession session ) {
		final ScheduledFuture<?> future = (ScheduledFuture<?>)session.getAttribute( HEARTBEAT_FUTURE_KEY );

		if( future != null ) {
			future.cancel( false );
			session.removeAttribute( HEARTBEAT_FUTURE_KEY );
		}

		final ScheduledExecutorService executor = (ScheduledExecutorService)session.getAttribute( HEARTBEAT_EXECUTOR_KEY );

		if( executor != null ) {
			executor.shutdown();
			session.removeAttribute( HEARTBEAT_EXECUTOR_KEY );
			logger.debug( "Stopped heartbeat for {}", session.getRemoteAddress() );
		}
	}

	/**
	 * Called when a text message is received. Override this to handle heartbeat responses
	 * or filter out heartbeat messages before processing.
	 *
	 * @param session the WebSocket session
	 * @param message the message received
	 * @return true if this was a heartbeat message (and should not be processed further)
	 */
	protected boolean isHeartbeatMessage( WOWebSocketSession session, String message ) {
		return "ping".equals( message ) || "pong".equals( message );
	}
}