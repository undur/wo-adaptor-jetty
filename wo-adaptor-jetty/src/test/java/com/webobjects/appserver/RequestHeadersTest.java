package com.webobjects.appserver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
	 * Header names are case-insensitive, so fields whose names differ only in case are one header, with every value in the
	 * order the fields came. Keyed by the name as sent, one value replaced the other depending on the map's iteration order,
	 * which depends on the other headers: hence the padding. Behind Apache this let a client sending its own remote_addr
	 * choose the address the application saw (#17).
	 */
	@ParameterizedTest
	@ValueSource(ints = { 0, 1, 2, 3, 5, 8 })
	public void namesDifferingOnlyInCaseAreOneHeader( final int padding ) {
		final StringBuilder extra = new StringBuilder();

		for( int i = 0; i < padding; i++ ) {
			extra.append( "X-Padding-" ).append( (char)('A' + i) ).append( ": " ).append( i ).append( "\r\n" );
		}

		final WORequest request = captured( "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nremote_addr: 6.6.6.6\r\n" + extra + "REMOTE_ADDR: 10.0.0.1\r\n\r\n" );
		assertEquals( List.of( "6.6.6.6", "10.0.0.1" ), request.headersForKey( "remote_addr" ) );
		assertEquals( List.of( "6.6.6.6", "10.0.0.1" ), request.headersForKey( "REMOTE_ADDR" ) );
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
