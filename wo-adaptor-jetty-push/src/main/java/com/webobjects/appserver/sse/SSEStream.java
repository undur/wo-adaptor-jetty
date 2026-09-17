package com.webobjects.appserver.sse;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.webobjects.appserver.WOResponse;

/**
 * A server-sent events stream: an open-ended HTTP response the server keeps writing events to.
 *
 * Usage, from a direct action or any other place that produces a WOResponse:
 *
 * <pre>
 * final SSEStream stream = new SSEStream();
 * someRegistryOfListeners.add( stream );        // keep a reference somewhere, and send to it later...
 * stream.send( "hello", "connected" );          // ...or right away
 * return stream.response();                     // WO returns immediately; the response stays open
 * </pre>
 *
 * The response carries no length, so the adaptor streams it with chunked transfer encoding until the stream is closed,
 * writing each event as it is sent. Events are queued, so {@link #send} never blocks and may be called from any thread.
 * While nothing is sent, a comment line is written at {@code keepAliveInterval} (default 30 seconds) so that proxies and
 * the connector's idle timeout don't take the quiet connection for a dead one; keep the interval below both.
 *
 * The stream ends when {@link #close} is called, or when the client goes away - the adaptor then closes the underlying
 * InputStream, which is how {@link #isOpen} turns false and {@link #onClose} listeners run. Sending to a closed stream is
 * a silent no-op, so a broadcaster need not check first.
 *
 * Requires an adaptor that streams unknown-length responses (wo-adaptor-jetty does). WO's classic adaptor would send the
 * response with a Content-Length of 0 and the events would never leave the server. Note also that a long-lived response
 * holds a QoS permit for its lifetime if JettyMaxConcurrentRequests is set; exclude event paths with JettyQoSExcludedPaths.
 */
public final class SSEStream implements Closeable {

	public static final String CONTENT_TYPE = "text/event-stream";

	private static final Duration DEFAULT_KEEP_ALIVE_INTERVAL = Duration.ofSeconds( 30 );
	private static final byte[] KEEP_ALIVE = ": keep-alive\n\n".getBytes( StandardCharsets.UTF_8 );
	private static final byte[] END = new byte[0];

	private final BlockingQueue<byte[]> _queue = new LinkedBlockingQueue<>();
	private final EventInputStream _inputStream = new EventInputStream();
	private final List<Runnable> _closeListeners = new CopyOnWriteArrayList<>();
	private final AtomicBoolean _closed = new AtomicBoolean();
	private final long _keepAliveMillis;

	public SSEStream() {
		this( DEFAULT_KEEP_ALIVE_INTERVAL );
	}

	public SSEStream( final Duration keepAliveInterval ) {
		_keepAliveMillis = keepAliveInterval.toMillis();
	}

	/**
	 * @return The response to return from your action. Status 200, {@code text/event-stream}, no caching, no length.
	 */
	public WOResponse response() {
		final WOResponse response = new WOResponse();
		response.setStatus( 200 );
		response.setHeader( CONTENT_TYPE, "content-type" );
		response.setHeader( "no-cache", "cache-control" );
		response.setHeader( "no", "x-accel-buffering" ); // Tells nginx-style front ends not to buffer the response
		response.setContentStream( _inputStream, 4096, 0 ); // Length 0 = unknown: the adaptor streams until EOF
		return response;
	}

	/**
	 * Send an unnamed event ("message" on the client side). Multi-line data is sent as multiple data lines, as the format
	 * requires.
	 */
	public void send( final String data ) {
		send( null, data, null );
	}

	/**
	 * Send a named event. On the client, {@code eventSource.addEventListener( event, ... )} receives it.
	 */
	public void send( final String event, final String data ) {
		send( event, data, null );
	}

	/**
	 * Send a named event with an id. The client remembers the last id it saw and sends it as {@code Last-Event-ID} when
	 * it reconnects, which is how an app can resume a client that dropped off.
	 */
	public void send( final String event, final String data, final String id ) {
		final StringBuilder sb = new StringBuilder();

		if( event != null ) {
			sb.append( "event: " ).append( event ).append( '\n' );
		}

		if( id != null ) {
			sb.append( "id: " ).append( id ).append( '\n' );
		}

		for( final String line : (data == null ? "" : data).split( "\r?\n", -1 ) ) {
			sb.append( "data: " ).append( line ).append( '\n' );
		}

		sb.append( '\n' );
		enqueue( sb.toString().getBytes( StandardCharsets.UTF_8 ) );
	}

	/**
	 * Send a comment line. Clients ignore it; useful as an application-level heartbeat or for debugging.
	 */
	public void comment( final String text ) {
		enqueue( (": " + text + "\n\n").getBytes( StandardCharsets.UTF_8 ) );
	}

	/**
	 * @return true until the stream is closed, by us or by the client disconnecting
	 */
	public boolean isOpen() {
		return !_closed.get();
	}

	/**
	 * Register a listener that runs once, when the stream closes for whatever reason. A listener registered on an already
	 * closed stream runs immediately.
	 */
	public void onClose( final Runnable listener ) {
		_closeListeners.add( listener );

		if( _closed.get() ) {
			listener.run();
		}
	}

	/**
	 * End the stream. The response completes once queued events have been written. Idempotent.
	 */
	@Override
	public void close() {
		if( _closed.compareAndSet( false, true ) ) {
			_queue.add( END );

			for( final Runnable listener : _closeListeners ) {
				listener.run();
			}
		}
	}

	private void enqueue( final byte[] bytes ) {
		if( !_closed.get() ) {
			_queue.add( bytes );
		}
	}

	/**
	 * The InputStream the adaptor reads. Each read returns the next queued event, blocking (with keep-alives) while the
	 * queue is empty, and reports EOF once the stream has been closed and drained. The adaptor closes it when the client
	 * disconnects, which closes the SSEStream.
	 */
	private final class EventInputStream extends InputStream {

		private byte[] _current;
		private int _position;

		@Override
		public int read() throws IOException {
			final byte[] one = new byte[1];
			return read( one, 0, 1 ) == -1 ? -1 : one[0] & 0xff;
		}

		@Override
		public int read( final byte[] buffer, final int offset, final int length ) throws IOException {
			if( length == 0 ) {
				return 0;
			}

			while( true ) {
				if( _current != null && _position < _current.length ) {
					final int n = Math.min( length, _current.length - _position );
					System.arraycopy( _current, _position, buffer, offset, n );
					_position += n;
					return n;
				}

				final byte[] next;

				try {
					next = _queue.poll( _keepAliveMillis, TimeUnit.MILLISECONDS );
				}
				catch( final InterruptedException e ) {
					Thread.currentThread().interrupt();
					return -1;
				}

				if( next == END ) {
					return -1;
				}

				_current = next == null ? KEEP_ALIVE : next;
				_position = 0;
			}
		}

		@Override
		public void close() {
			SSEStream.this.close();
		}
	}
}
