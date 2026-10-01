package com.webobjects.appserver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * How request headers reach the WORequest
 */
public class RequestHeadersTest {

	private static TestServer server;

	@BeforeAll
	public static void start() {
		server = TestServer.start();
	}

	@AfterAll
	public static void stop() {
		server.close();
	}

	private static WORequest captured( final String rawRequest ) {
		final AtomicReference<WORequest> captured = new AtomicReference<>();

		TestApplication.respondWith( request -> {
			captured.set( request );
			return TestApplication.text( "ok" );
		} );

		assertEquals( 200, server.http().send( rawRequest ).status() );
		return captured.get();
	}

	/**
	 * A request may carry the same header name more than once. Every value must survive; earlier versions kept only the
	 * last.
	 */
	@Test
	public void repeatedHeaderNamesKeepEveryValue() {
		final WORequest request = captured( "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nX-Multi: first\r\nX-Multi: second\r\nX-Multi: third\r\n\r\n" );
		assertEquals( List.of( "first", "second", "third" ), request.headersForKey( "x-multi" ) );
	}

	/**
	 * A single header whose value contains commas is one value, not a list. Splitting it corrupts cookies, dates and
	 * anything else that legitimately contains a comma.
	 */
	@Test
	public void commaInAValueDoesNotSplitIt() {
		final WORequest request = captured( "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nX-Date: Wed, 01 Oct 2026 10:00:00 GMT\r\nCookie: a=1, b=2\r\n\r\n" );
		assertEquals( List.of( "Wed, 01 Oct 2026 10:00:00 GMT" ), request.headersForKey( "x-date" ) );
		assertEquals( 1, request.headersForKey( "cookie" ).count() );
	}
}
