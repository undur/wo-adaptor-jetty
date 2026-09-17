package com.webobjects.appserver.websocket.examples;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WORequest;
import com.webobjects.appserver.websocket.WOWebSocketHandler;
import com.webobjects.appserver.websocket.WOWebSocketSession;

/**
 * Example WebSocket handler that echoes messages back to the client.
 *
 * To use this handler, register it in your Application class:
 *
 * <pre>
 * {@literal @}Override
 * public void finishInitialization() {
 *     super.finishInitialization();
 *     WOWebSocketRegistry.register("/ws/echo", EchoWebSocketHandler.class);
 * }
 * </pre>
 *
 * Then connect to it from JavaScript:
 *
 * <pre>
 * const ws = new WebSocket('ws://localhost:1200/ws/echo');
 *
 * ws.onopen = () => {
 *     console.log('Connected!');
 *     ws.send('Hello, WebSocket!');
 * };
 *
 * ws.onmessage = (event) => {
 *     console.log('Received:', event.data);
 * };
 *
 * ws.onclose = () => {
 *     console.log('Disconnected');
 * };
 * </pre>
 */

public class EchoWebSocketHandler extends WOWebSocketHandler {

	private static final Logger logger = LoggerFactory.getLogger( EchoWebSocketHandler.class );

	@Override
	public void onConnect( WOWebSocketSession session, WORequest initiatingRequest ) {

		logger.info( "WebSocket connected: {}", session.getRemoteAddress() );

		// Start a heartbeat to keep the connection alive (send "ping" every 2 minutes)
		startHeartbeat( session, 120 );

		// Send a welcome message
		try {
			session.sendText( "Welcome to the echo server!" );
		}
		catch( IOException e ) {
			logger.error( "Failed to send welcome message", e );
		}
	}

	@Override
	public void onTextMessage( WOWebSocketSession session, String message ) {

		// Filter out heartbeat messages
		if( isHeartbeatMessage( session, message ) ) {
			logger.debug( "Received heartbeat: {}", message );
			return;
		}

		logger.debug( "Received text message: {}", message );

		// Echo the message back with a prefix
		try {
			session.sendText( "Echo: " + message );
		}
		catch( IOException e ) {
			logger.error( "Failed to send echo message", e );
		}
	}

	@Override
	public void onBinaryMessage( WOWebSocketSession session, ByteBuffer data ) {
		logger.debug( "Received binary message of {} bytes", data.remaining() );

		// Echo the binary data back
		try {
			data.rewind(); // Reset position to beginning
			session.sendBinary( data );
		}
		catch( IOException e ) {
			logger.error( "Failed to send binary echo", e );
		}
	}

	@Override
	public void onClose( WOWebSocketSession session, int statusCode, String reason ) {
		logger.info( "WebSocket closed: {} (status={}, reason={})", session.getRemoteAddress(), statusCode, reason );
		// Heartbeat is automatically stopped when connection closes
		stopHeartbeat( session );
	}

	@Override
	public void onError( WOWebSocketSession session, Throwable cause ) {
		logger.error( "WebSocket error for " + session.getRemoteAddress(), cause );
	}
}