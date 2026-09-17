package com.webobjects.appserver.websocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.websocket.api.Session;

/**
 * Wraps a Jetty WebSocket Session for our use
 */

public class WOJettyWebSocketSession implements WOWebSocketSession {

	/**
	 * The underlying Jetty session
	 */
	private final Session _jettySession;

	/**
	 * FIXME: We should really have a session object, or some other typed info associated with the session, rather than a simple Map // Hugi 2025-11-13
	 */
	private final Map<String, Object> _attributes = new ConcurrentHashMap<>();

	public WOJettyWebSocketSession( Session jettySession ) {
		_jettySession = jettySession;
	}

	@Override
	public boolean isOpen() {
		return _jettySession.isOpen();
	}

	@Override
	public void sendText( String message ) throws IOException {

		if( !isOpen() ) {
			throw new IOException( "WebSocket session is not open" );
		}

		_jettySession.sendText( message, null );
	}

	@Override
	public void sendBinary( ByteBuffer data ) throws IOException {

		if( !isOpen() ) {
			throw new IOException( "WebSocket session is not open" );
		}

		_jettySession.sendBinary( data, null );
	}

	@Override
	public void close() throws IOException {
		if( isOpen() ) {
			_jettySession.close();
		}
	}

	@Override
	public void close( int statusCode, String reason ) throws IOException {
		if( isOpen() ) {
			_jettySession.close( statusCode, reason, null );
		}
	}

	@Override
	public String getRemoteAddress() {

		if( _jettySession.getRemoteSocketAddress() != null ) {
			return _jettySession.getRemoteSocketAddress().toString();
		}

		return "unknown";
	}

	@Override
	public Object getAttribute( String key ) {
		return _attributes.get( key );
	}

	@Override
	public void setAttribute( String key, Object value ) {
		_attributes.put( key, value );
	}

	@Override
	public void removeAttribute( String key ) {
		_attributes.remove( key );
	}

	/**
	 * @return the underlying Jetty session
	 */
	Session jettySession() {
		return _jettySession;
	}
}