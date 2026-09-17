package com.webobjects.appserver.websocket;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.webobjects.appserver.WORequest;

/**
 * Jetty WebSocket listener that delegates to a WOWebSocketHandler.
 */

public class WOJettyWebSocketListener implements Session.Listener {

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

		// Start demanding messages
		session.demand();
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

		// Demand more data for the next message
		if( _woWebSocketSession != null && _woWebSocketSession.isOpen() ) {
			_woWebSocketSession.jettySession().demand();
		}
	}

	@Override
	public void onWebSocketBinary( ByteBuffer payload, Callback callback ) {
		try {
			_handler.onBinaryMessage( _woWebSocketSession, payload );
			callback.succeed();
		}
		catch( Exception e ) {
			logger.error( "Error in WebSocket onBinaryMessage handler", e );
			_handler.onError( _woWebSocketSession, e );
			callback.fail( e );
		}
	}

	@Override
	public void onWebSocketClose( int statusCode, String reason ) {
		try {
			_handler.onClose( _woWebSocketSession, statusCode, reason );
		}
		catch( Exception e ) {
			logger.error( "Error in WebSocket onClose handler", e );
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