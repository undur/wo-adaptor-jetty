package com.webobjects.appserver.sse;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A set of open {@link SSEStream}s to broadcast to - every client subscribed to the same feed. Streams remove themselves
 * when they close, so the hub only ever holds live connections.
 *
 * <pre>
 * private static final SSEHub CLOCK = new SSEHub();
 *
 * public WOActionResults clockAction() {
 *     return CLOCK.open().response();             // subscribe the caller
 * }
 *
 * // elsewhere, e.g. from a timer:
 * CLOCK.broadcast( "tick", Instant.now().toString() );
 * </pre>
 */
public final class SSEHub {

	private final Set<SSEStream> _streams = ConcurrentHashMap.newKeySet();

	/**
	 * Create a stream, add it to the hub and return it. Return {@code stream.response()} to the client.
	 */
	public SSEStream open() {
		return add( new SSEStream() );
	}

	public SSEStream open( final Duration keepAliveInterval ) {
		return add( new SSEStream( keepAliveInterval ) );
	}

	/**
	 * Add an existing stream. It leaves the hub automatically when it closes.
	 */
	public SSEStream add( final SSEStream stream ) {
		_streams.add( stream );
		stream.onClose( () -> _streams.remove( stream ) );
		return stream;
	}

	public void broadcast( final String data ) {
		broadcast( null, data, null );
	}

	public void broadcast( final String event, final String data ) {
		broadcast( event, data, null );
	}

	public void broadcast( final String event, final String data, final String id ) {
		for( final SSEStream stream : _streams ) {
			stream.send( event, data, id );
		}
	}

	/**
	 * @return The number of open streams
	 */
	public int size() {
		return _streams.size();
	}

	/**
	 * Close every stream, e.g. on application shutdown.
	 */
	public void closeAll() {
		for( final SSEStream stream : _streams ) {
			stream.close();
		}
	}
}
