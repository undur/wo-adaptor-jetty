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

	@ParameterizedTest
	@ValueSource(strings = { "/a\\b", "/a%00b", "/a%09b", "/a%2Fb", "/../etc" })
	public void suspiciousPathsAreStillRefused( final String path ) {
		assertEquals( 400, server.http().get( path ).status(), path );
	}
}
