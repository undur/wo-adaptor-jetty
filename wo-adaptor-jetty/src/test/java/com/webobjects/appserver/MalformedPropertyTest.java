package com.webobjects.appserver;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A typo in a concurrency limit stops the adaptor at startup, rather than silently running it without backpressure
 * (#11). The adaptor reads its configuration when it loads, so this class runs in a JVM of its own.
 */
public class MalformedPropertyTest {

	static {
		System.setProperty( "JettyMaxConcurrentRequests", "5O" );
	}

	@Test
	public void aMalformedConcurrencyLimitStopsStartup() {
		final Throwable thrown = assertThrows( Throwable.class, TestServer::start );

		Throwable cause = thrown;

		while( cause.getCause() != null && !(cause instanceof IllegalArgumentException) ) {
			cause = cause.getCause();
		}

		assertTrue( cause instanceof IllegalArgumentException, "failed for the reason we expect: " + thrown );
		assertTrue( cause.getMessage().contains( "JettyMaxConcurrentRequests" ), cause.getMessage() );
	}
}
