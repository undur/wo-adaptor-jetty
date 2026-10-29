package com.webobjects.appserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.handler.QoSHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The default server with a concurrency limit of one, and one path excluded from it.
 *
 * The adaptor reads its configuration once, when it is loaded, so it is set here before anything touches it; this class
 * runs in a JVM of its own.
 */
public class QoSTest {

	static {
		System.setProperty( "JettyMaxConcurrentRequests", "1" );
		System.setProperty( "JettyQoSExcludedPaths", "/events/*" );
	}

	private static TestServer server;

	@BeforeAll
	public static void start() {
		server = TestServer.start();
	}

	@AfterAll
	public static void stop() {
		server.close();
	}

	@Test
	public void aConcurrencyLimitInstallsTheQoSHandler() {
		assertInstanceOf( QoSHandler.class, server.outermostHandler() );
		assertEquals( 1, ((QoSHandler)server.outermostHandler()).getMaxRequestCount() );
	}

	/**
	 * While one request holds the only permit, a request to an excluded path is served at once, and any other request
	 * waits for the permit.
	 *
	 * The requests are sent from threads of the test's own: the common pool may have only two, which the held and the
	 * queued request occupy, leaving the excluded request unsent.
	 */
	@Test
	public void excludedPathsBypassTheLimit() throws Exception {
		final CountDownLatch holding = new CountDownLatch( 1 );
		final CountDownLatch release = new CountDownLatch( 1 );

		TestApplication.respondWith( request -> {
			if( request.uri().startsWith( "/hold" ) ) {
				holding.countDown();

				try {
					release.await( 10, TimeUnit.SECONDS );
				}
				catch( final InterruptedException e ) {
					Thread.currentThread().interrupt();
				}
			}

			return TestApplication.echo( request );
		} );

		final ExecutorService clients = Executors.newCachedThreadPool();

		try {
			final CompletableFuture<RawHttp.Response> held = CompletableFuture.supplyAsync( () -> server.http().get( "/hold" ), clients );
			assertTrue( holding.await( 5, TimeUnit.SECONDS ), "the first request holds the permit" );

			final CompletableFuture<RawHttp.Response> queued = CompletableFuture.supplyAsync( () -> server.http().get( "/ordinary" ), clients );
			final RawHttp.Response excluded = CompletableFuture.supplyAsync( () -> server.http().get( "/events/feed" ), clients ).get( 3, TimeUnit.SECONDS );

			assertEquals( 200, excluded.status(), "the excluded path is served while the permit is taken" );
			assertEquals( "GET /events/feed", excluded.bodyString() );

			Thread.sleep( 500 );
			assertFalse( queued.isDone(), "an ordinary request waits for the permit" );

			release.countDown();
			assertEquals( 200, held.get( 5, TimeUnit.SECONDS ).status() );
			assertEquals( 200, queued.get( 5, TimeUnit.SECONDS ).status() );
		}
		finally {
			clients.shutdownNow();
		}
	}
}
