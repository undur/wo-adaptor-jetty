package com.webobjects.appserver;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Which request paths reach the application. Jetty's default URI compliance refuses characters the classic adaptor
 * passes through, such as '|'; the adaptor relaxes exactly that, and nothing that matters for safety.
 */
public class RequestPathTest {

	private static TestServer server;

	@BeforeAll
	public static void start() {
		server = TestServer.start();
		TestApplication.respondWith( TestApplication::echo );
	}

	@AfterAll
	public static void stop() {
		server.close();
	}

	@Test
	public void pipeInAPathReachesTheApplication() {
		final RawHttp.Response response = server.http().get( "/a|b" );
		assertEquals( 200, response.status() );
		assertEquals( "GET /a|b", response.bodyString() );
	}

	/**
	 * An encoded slash is part of a segment, not a separator. It reaches WO still encoded, so the path's structure is kept
	 * (#9).
	 */
	@Test
	public void encodedSlashInASegmentReachesTheApplicationEncoded() {
		final RawHttp.Response response = server.http().get( "/document/2008-10-01+Etc%2FGMT.jpg" );
		assertEquals( 200, response.status() );
		assertEquals( "GET /document/2008-10-01+Etc%2FGMT.jpg", response.bodyString() );
	}

	@ParameterizedTest
	@ValueSource(strings = { "/a\\b", "/a%00b", "/a%09b", "/../etc", "/a/%2E%2E/etc", "/a/%2e/b" })
	public void suspiciousPathsAreStillRefused( final String path ) {
		assertEquals( 400, server.http().get( path ).status(), path );
	}
}
