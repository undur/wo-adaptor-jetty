package com.webobjects.appserver.websocket;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WORequest;

/**
 * Jetty WebSocket listener that delegates to a WOWebSocketHandler.
 *
 * Auto-demanding, so Jetty reads the next frame once a callback has returned. With manual demand, every path through every
 * callback must demand, and one that doesn't - as the binary path once didn't - stops the session, including the peer's
 * CLOSE.
 */

public class WOJettyWebSocketListener implements Session.Listener.AutoDemanding {

	private static final Logger logger = LoggerFactory.getLogger( WOJettyWebSocketListener.class );

	private final WOWebSocketHandler _handler;
	private final WORequest _initialRequest;
	private WOJettyWebSocketSession _woWebSocketSession;

	public WOJettyWebSocketListener( WOWebSocketHandler handler, WORequest initialRequest ) {
		_handler = handler;
		_initialRequest = initialRequest;
	}

	@Override
	public void onWebSocketOpen( Session session ) {
		_woWebSocketSession = new WOJettyWebSocketSession( session );

		try {
			_handler.onConnect( _woWebSocketSession, _initialRequest );
		}
		catch( Exception e ) {
			logger.error( "Error in WebSocket onConnect handler", e );
			_handler.onError( _woWebSocketSession, e );
		}
	}

	@Override
	public void onWebSocketText( String message ) {
		try {
			_handler.onTextMessage( _woWebSocketSession, message );
		}
		catch( Exception e ) {
			logger.error( "Error in WebSocket onTextMessage handler", e );
			_handler.onError( _woWebSocketSession, e );
		}
	}

	/**
	 * The payload buffer belongs to Jetty, which recycles it once the callback completes, so the handler gets a copy it may
	 * keep, hand to an asynchronous send or read from another thread.
	 */
	@Override
	public void onWebSocketBinary( ByteBuffer payload, Callback callback ) {
		final ByteBuffer copy = ByteBuffer.allocate( payload.remaining() ).put( payload ).flip();
		callback.succeed();

		try {
			_handler.onBinaryMessage( _woWebSocketSession, copy );
		}
		catch( Exception e ) {
			logger.error( "Error in WebSocket onBinaryMessage handler", e );
			_handler.onError( _woWebSocketSession, e );
		}
	}

	@Override
	public void onWebSocketClose( int statusCode, String reason, Callback callback ) {
		try {
			_handler.onClose( _woWebSocketSession, statusCode, reason );
		}
		catch( Exception e ) {
			logger.error( "Error in WebSocket onClose handler", e );
		}
		finally {
			// A no-op if the handler never started one, or stopped it itself
			_handler.stopHeartbeat( _woWebSocketSession );
			callback.succeed();
		}
	}

	@Override
	public void onWebSocketError( Throwable cause ) {
		try {
			_handler.onError( _woWebSocketSession, cause );
		}
		catch( Exception e ) {
			logger.error( "Error in WebSocket onError handler", e );
		}
	}
}