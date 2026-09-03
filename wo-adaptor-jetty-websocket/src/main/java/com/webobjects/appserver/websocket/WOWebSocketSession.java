package com.webobjects.appserver.websocket;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Represents a WebSocket connection session.
 * Provides methods to send messages and manage the connection.
 */
public interface WOWebSocketSession {

	/**
	 * @return true if the WebSocket connection is currently open
	 */
	boolean isOpen();

	/**
	 * Send a text message to the client
	 *
	 * @param message the text message to send
	 * @throws IOException if the message cannot be sent
	 */
	void sendText( String message ) throws IOException;

	/**
	 * Send a binary message to the client
	 *
	 * @param data the binary data to send
	 * @throws IOException if the message cannot be sent
	 */
	void sendBinary( ByteBuffer data ) throws IOException;

	/**
	 * Close the WebSocket connection
	 *
	 * @throws IOException if the connection cannot be closed
	 */
	void close() throws IOException;

	/**
	 * Close the WebSocket connection with a specific status code and reason
	 *
	 * @param statusCode the WebSocket close status code
	 * @param reason the reason for closing
	 * @throws IOException if the connection cannot be closed
	 */
	void close( int statusCode, String reason ) throws IOException;

	/**
	 * @return the remote address of the connected client
	 */
	String getRemoteAddress();

	/**
	 * Get a user-defined attribute associated with this session
	 *
	 * @param key the attribute key
	 * @return the attribute value, or null if not set
	 */
	Object getAttribute( String key );

	/**
	 * Set a user-defined attribute for this session
	 *
	 * @param key the attribute key
	 * @param value the attribute value
	 */
	void setAttribute( String key, Object value );

	/**
	 * Remove a user-defined attribute
	 *
	 * @param key the attribute key
	 */
	void removeAttribute( String key );
}