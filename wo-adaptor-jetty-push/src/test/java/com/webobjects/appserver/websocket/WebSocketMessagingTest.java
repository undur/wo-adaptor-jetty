package com.webobjects.appserver.websocket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.server.ServerWebSocketContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.webobjects.appserver.TestServer;
import com.webobjects.appserver.WORequest;

/**
 * Messages through a real WebSocket connection (#13)
 */
public class WebSocketMessagingTest {

	private static TestServer server;

	/**
	 * Answers text with "text:" and binary with "binary:", keeps every binary buffer it is given, and notes onClose
	 */
	public static class RecordingHandler extends WOWebSocketHandler {

		static final List<ByteBuffer> received = new CopyOnWriteArrayList<>();
		static volatile CountDownLatch closed;
		static volatile WOWebSocketSession last;

		@Override
		public void onConnect( WOWebSocketSession session, WORequest request ) {
			last = session;
			startHeartbeat( session, 3600 );
		}

		@Override
		public void onTextMessage( WOWebSocketSession session, String message ) {
			send( session, "text:" + message );
		}

		@Override
		public void onBinaryMessage( WOWebSocketSession session, ByteBuffer data ) {
			received.add( data );
			send( session, "binary:" + StandardCharsets.UTF_8.decode( data.duplicate() ) );
		}

		@Override
		public void onClose( WOWebSocketSession session, int statusCode, String reason ) {
			if( session.getAttribute( "_heartbeat_executor" ) != null ) {
				closed.countDown();
			}
		}

		private static void send( WOWebSocketSession session, String message ) {
			try {
				session.sendText( message );
			}
			catch( Exception e ) {
				throw new RuntimeException( e );
			}
		}
	}

	@BeforeAll
	public static void start() {
		WOWebSocketRegistry.register( "/ws/recording", RecordingHandler.class );
		server = TestServer.start();
	}

	@AfterAll
	public static void stop() {
		server.close();
	}

	@BeforeEach
	public void reset() {
		RecordingHandler.received.clear();
		RecordingHandler.closed = new CountDownLatch( 1 );
	}

	/**
	 * A binary message used to stop the session: nothing further was delivered, not even the peer's CLOSE
	 */
	@Test
	public void aBinaryMessageDoesNotStopTheSession() throws Exception {
		final Client client = Client.connect( server.port() );

		client.socket.sendBinary( bytes( "one" ), true ).join();
		assertEquals( "binary:one", client.next() );

		client.socket.sendText( "two", true ).join();
		assertEquals( "text:two", client.next() );

		client.socket.sendClose( WebSocket.NORMAL_CLOSURE, "done" ).join();
		assertTrue( RecordingHandler.closed.await( 5, TimeUnit.SECONDS ), "onClose ran, with the heartbeat still to stop" );
	}

	/**
	 * The adaptor stops a handler's heartbeat after onClose(), as WOWebSocketHandler documents
	 */
	@Test
	public void closingStopsTheHeartbeat() throws Exception {
		final Client client = Client.connect( server.port() );
		client.socket.sendText( "hello", true ).join();
		assertEquals( "text:hello", client.next() );

		final WOWebSocketSession session = RecordingHandler.last;
		final ScheduledExecutorService heartbeat = (ScheduledExecutorService)session.getAttribute( "_heartbeat_executor" );
		assertNotNull( heartbeat );

		client.socket.sendClose( WebSocket.NORMAL_CLOSURE, "done" ).join();
		assertTrue( RecordingHandler.closed.await( 5, TimeUnit.SECONDS ) );

		final long deadline = System.currentTimeMillis() + 5_000;

		while( session.getAttribute( "_heartbeat_executor" ) != null && System.currentTimeMillis() < deadline ) {
			Thread.sleep( 50 );
		}

		assertNull( session.getAttribute( "_heartbeat_executor" ) );
		assertTrue( heartbeat.isShutdown() );
	}

	/**
	 * A handler may keep the buffer it is given; frames arriving later must not overwrite it
	 */
	@Test
	public void aKeptBufferSurvivesLaterFrames() throws Exception {
		final Client client = Client.connect( server.port() );

		for( int i = 0; i < 20; i++ ) {
			client.socket.sendBinary( bytes( "message-" + i ), true ).join();
			assertEquals( "binary:message-" + i, client.next() );
		}

		for( int i = 0; i < 20; i++ ) {
			assertArrayEquals( ("message-" + i).getBytes( StandardCharsets.UTF_8 ), contents( RecordingHandler.received.get( i ) ), "message " + i );
		}

		client.socket.sendClose( WebSocket.NORMAL_CLOSURE, "done" ).join();
	}

	/**
	 * With JettyWebSocketIdleTimeout unset, a connection that goes quiet is eventually closed by Jetty's default timeout,
	 * rather than kept forever
	 */
	@Test
	public void anUnsetIdleTimeoutLeavesJettysDefault() {
		final ServerWebSocketContainer container = ServerWebSocketContainer.get( server.jettyServer().getContext() );
		assertNotNull( container );
		assertEquals( Duration.ofSeconds( 30 ), container.getIdleTimeout() );
	}

	private static ByteBuffer bytes( String text ) {
		return ByteBuffer.wrap( text.getBytes( StandardCharsets.UTF_8 ) );
	}

	private static byte[] contents( ByteBuffer buffer ) {
		final ByteBuffer view = buffer.duplicate().rewind();
		final byte[] bytes = new byte[view.remaining()];
		view.get( bytes );
		return bytes;
	}

	private record Client( WebSocket socket, BlockingQueue<String> messages ) {

		static Client connect( int port ) {
			final BlockingQueue<String> messages = new LinkedBlockingQueue<>();

			final WebSocket socket = HttpClient.newHttpClient().newWebSocketBuilder().buildAsync( URI.create( "ws://localhost:" + port + "/ws/recording" ), new WebSocket.Listener() {
				private final StringBuilder partial = new StringBuilder();

				@Override
				public CompletionStage<?> onText( WebSocket webSocket, CharSequence data, boolean last ) {
					partial.append( data );

					if( last ) {
						messages.add( partial.toString() );
						partial.setLength( 0 );
					}

					webSocket.request( 1 );
					return null;
				}
			} ).join();

			return new Client( socket, messages );
		}

		String next() throws InterruptedException {
			final String message = messages.poll( 5, TimeUnit.SECONDS );
			assertNotNull( message, "a reply within 5 seconds" );
			return message;
		}
	}
}
