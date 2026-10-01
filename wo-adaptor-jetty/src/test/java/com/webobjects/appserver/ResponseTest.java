package com.webobjects.appserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.webobjects.foundation.NSData;
import com.webobjects.foundation.NSRange;

/**
 * How a WOResponse is written to the wire
 */
public class ResponseTest {

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
	 * Every value of a multi-valued header is a field of its own. Joining them with commas is only valid for list-typed
	 * headers; it breaks Set-Cookie and makes WWW-Authenticate ambiguous, since a challenge's own parameters contain
	 * commas (#12).
	 */
	@Test
	public void eachHeaderValueIsAFieldOfItsOwn() {
		TestApplication.respondWith( request -> {
			final WOResponse response = TestApplication.text( "ok" );
			response.appendHeader( "Basic realm=\"a, b\"", "www-authenticate" );
			response.appendHeader( "Bearer realm=\"x\", error=\"invalid_token\"", "www-authenticate" );
			response.appendHeader( "</style.css>; rel=preload; as=style", "link" );
			response.appendHeader( "</app.js>; rel=preload; as=script", "link" );
			response.appendHeader( "first", "x-probe" );
			response.appendHeader( "second", "x-probe" );
			response.appendHeader( "a=1; Path=/", "set-cookie" );
			response.appendHeader( "b=2; Path=/", "set-cookie" );
			return response;
		} );

		final RawHttp.Response response = server.http().get( "/" );

		assertEquals( List.of( "Basic realm=\"a, b\"", "Bearer realm=\"x\", error=\"invalid_token\"" ), response.headerLines( "www-authenticate" ) );
		assertEquals( List.of( "</style.css>; rel=preload; as=style", "</app.js>; rel=preload; as=script" ), response.headerLines( "link" ) );
		assertEquals( List.of( "first", "second" ), response.headerLines( "x-probe" ) );
		assertEquals( List.of( "a=1; Path=/", "b=2; Path=/" ), response.headerLines( "set-cookie" ) );
		assertEquals( 1, response.headerLines( "content-type" ).size() );
	}

	@Test
	public void contentIsSentWithItsLength() {
		TestApplication.respondWith( request -> TestApplication.text( "hello there" ) );

		final RawHttp.Response response = server.http().get( "/" );
		assertEquals( "11", response.header( "content-length" ) );
		assertFalse( response.chunked() );
		assertEquals( "hello there", response.bodyString() );
	}

	/**
	 * Content is written straight from the NSData's backing array, so it must honour the range the data occupies in it
	 */
	@Test
	public void contentThatIsPartOfALargerArrayIsSentExactly() {
		final byte[] backing = "xxxxxinside the rangexxxxx".getBytes( StandardCharsets.UTF_8 );

		TestApplication.respondWith( request -> {
			final WOResponse response = new WOResponse();
			response.setContent( new NSData( backing, new NSRange( 5, 16 ), true ) );
			return response;
		} );

		final RawHttp.Response response = server.http().get( "/" );
		assertEquals( "16", response.header( "content-length" ) );
		assertEquals( "inside the range", response.bodyString() );
	}

	/**
	 * Large content built up by appending goes out whole and exact
	 */
	@Test
	public void largeAppendedContentIsSentExactly() {
		final StringBuilder expected = new StringBuilder();

		for( int i = 0; i < 50_000; i++ ) {
			expected.append( i ).append( '\n' );
		}

		TestApplication.respondWith( request -> {
			final WOResponse response = new WOResponse();

			for( int i = 0; i < 50_000; i++ ) {
				response.appendContentString( i + "\n" );
			}

			return response;
		} );

		final RawHttp.Response response = server.http().get( "/" );
		assertEquals( String.valueOf( expected.length() ), response.header( "content-length" ) );
		assertEquals( expected.toString(), response.bodyString() );
	}

	@Test
	public void contentStreamWithALengthIsSentWithIt() {
		final byte[] content = "streamed with a length".getBytes( StandardCharsets.UTF_8 );

		TestApplication.respondWith( request -> {
			final WOResponse response = new WOResponse();
			response.setContentStream( new ByteArrayInputStream( content ), 4096, content.length );
			return response;
		} );

		final RawHttp.Response response = server.http().get( "/" );
		assertEquals( String.valueOf( content.length ), response.header( "content-length" ) );
		assertFalse( response.chunked() );
		assertEquals( "streamed with a length", response.bodyString() );
	}

	/**
	 * A content stream of unknown length (WOResponse records a negative length as 0) is streamed until it ends, with no
	 * Content-Length. Earlier versions sent "Content-Length: 0" and the content never left the server. This is what
	 * server-sent events and other open-ended responses rest on.
	 *
	 * On a persistent connection the body has to be chunked; with "Connection: close" Jetty may instead end it by closing
	 * the connection, so this keeps the connection open and reads to the terminating chunk.
	 */
	@Test
	public void contentStreamOfUnknownLengthIsChunked() {
		TestApplication.respondWith( request -> {
			final WOResponse response = new WOResponse();
			response.setContentStream( new ByteArrayInputStream( "streamed without a length".getBytes( StandardCharsets.UTF_8 ) ), 4096, 0 );
			return response;
		} );

		try( RawHttp.OpenResponse open = server.http().open( "/" )) {
			final String received = open.readUntil( "\r\n0\r\n\r\n", 5_000 );
			final String head = received.substring( 0, received.indexOf( "\r\n\r\n" ) ).toLowerCase();

			assertTrue( head.startsWith( "http/1.1 200" ), received );
			assertTrue( head.contains( "transfer-encoding: chunked" ), received );
			assertFalse( head.contains( "content-length" ), received );
			assertTrue( received.contains( "streamed without a length" ), received );
		}
	}
}
