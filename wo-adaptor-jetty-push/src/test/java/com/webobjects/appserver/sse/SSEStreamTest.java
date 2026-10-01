package com.webobjects.appserver.sse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.webobjects.appserver.RawHttp;
import com.webobjects.appserver.TestApplication;
import com.webobjects.appserver.TestServer;

/**
 * Server-sent events through the real adaptor
 */
public class SSEStreamTest {

	private static TestServer server;

	@BeforeAll
	public static void start() {
		server = TestServer.start();
	}

	@AfterAll
	public static void stop() {
		server.close();
	}

	/**
	 * A stream commits its response at once, so headers reach the client straight away rather than at the first event
	 * or keep-alive. Before this, a quiet feed behind a proxy whose idle timeout matched the keep-alive interval
	 * reconnected forever.
	 */
	@Test
	public void headersAndAnOpeningCommentArriveAtOnce() {
		TestApplication.respondWith( request -> new SSEStream().response() );

		try( RawHttp.OpenResponse open = server.http().open( "/events" )) {
			final long started = System.currentTimeMillis();
			final String received = open.readUntil( ": open", 3_000 );
			final long elapsed = System.currentTimeMillis() - started;

			assertTrue( received.contains( ": open" ), received );
			assertTrue( elapsed < 2_000, "arrived after " + elapsed + " ms, long before the default 10 second keep-alive" );

			final String head = received.substring( 0, received.indexOf( "\r\n\r\n" ) ).toLowerCase();
			assertTrue( head.contains( "content-type: text/event-stream" ), head );
			assertTrue( head.contains( "cache-control: no-store" ), head );
			assertTrue( head.contains( "transfer-encoding: chunked" ), head );
			assertFalse( head.contains( "content-length" ), head );
		}
	}

	@Test
	public void eventsAreWrittenInTheEventStreamFormat() {
		TestApplication.respondWith( request -> {
			final SSEStream stream = new SSEStream();
			stream.send( "greeting", "hello\nworld", "id-1" );
			return stream.response();
		} );

		try( RawHttp.OpenResponse open = server.http().open( "/events" )) {
			final String received = open.readUntil( "data: world\n\n", 3_000 );
			assertTrue( received.contains( "event: greeting\nid: id-1\ndata: hello\ndata: world\n\n" ), received );
		}
	}

	@Test
	public void aQuietStreamSendsKeepAlives() {
		TestApplication.respondWith( request -> new SSEStream( Duration.ofMillis( 200 ) ).response() );

		try( RawHttp.OpenResponse open = server.http().open( "/events" )) {
			final String received = open.readUntil( ": keep-alive\n\n: keep-alive", 3_000 );
			assertTrue( received.split( ": keep-alive", -1 ).length - 1 >= 2, received );
		}
	}

	@Test
	public void closingTheStreamEndsTheResponse() {
		TestApplication.respondWith( request -> {
			final SSEStream stream = new SSEStream();
			stream.send( "last words" );
			stream.close();
			return stream.response();
		} );

		try( RawHttp.OpenResponse open = server.http().open( "/events" )) {
			final String received = open.readUntil( "\r\n0\r\n\r\n", 3_000 );
			assertTrue( received.contains( "data: last words" ), received );
			assertTrue( received.endsWith( "\r\n0\r\n\r\n" ), "the response ended with the terminating chunk: " + received );
		}
	}

	/**
	 * A client going away is noticed at the next write, which closes the stream, runs its close listeners and removes it
	 * from the hub
	 */
	@Test
	public void aClientDisconnectingClosesTheStream() throws Exception {
		final SSEHub hub = new SSEHub();
		final AtomicReference<SSEStream> opened = new AtomicReference<>();
		final CountDownLatch closed = new CountDownLatch( 1 );

		TestApplication.respondWith( request -> {
			final SSEStream stream = hub.open( Duration.ofMillis( 100 ) );
			stream.onClose( closed::countDown );
			opened.set( stream );
			return stream.response();
		} );

		try( RawHttp.OpenResponse open = server.http().open( "/events" )) {
			open.readUntil( ": open", 3_000 );
			assertEquals( 1, hub.size() );
		}

		assertTrue( closed.await( 5, TimeUnit.SECONDS ), "onClose ran after the client went away" );
		assertFalse( opened.get().isOpen() );
		assertEquals( 0, hub.size() );
	}
}
