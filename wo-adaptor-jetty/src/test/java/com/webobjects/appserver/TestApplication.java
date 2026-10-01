package com.webobjects.appserver;

import java.util.Objects;
import java.util.function.Function;

/**
 * A real WOApplication for the tests, whose response to each request is whatever the current test says it is.
 *
 * WOApplication is a per-JVM singleton, so there is one instance, created on first use. Constructed outside
 * WOApplication.main() it gets a WONullAdaptor and binds no port of its own; the tests start the Jetty adaptor themselves
 * (see {@link TestServer}). Every test sets its own {@link #respondWith(Function) responder}.
 */
public class TestApplication extends WOApplication {

	private static TestApplication _instance;

	private static volatile Function<WORequest, WOActionResults> _responder = TestApplication::echo;

	/**
	 * @return The application, constructed on first use
	 */
	public static synchronized TestApplication instance() {
		if( _instance == null ) {
			_instance = new TestApplication();
		}

		return _instance;
	}

	/**
	 * Decide how the application answers requests from now on. The function runs on the request's own thread, inside
	 * dispatchRequest, so it can block, read the request body, or capture the WORequest for inspection.
	 */
	public static void respondWith( final Function<WORequest, WOActionResults> responder ) {
		instance();
		_responder = Objects.requireNonNull( responder );
	}

	/**
	 * The default responder: 200, with the method and URI as the body
	 */
	public static WOResponse echo( final WORequest request ) {
		return text( request.method() + " " + request.uri() );
	}

	/**
	 * @return A 200 response with the given text as its body
	 */
	public static WOResponse text( final String content ) {
		final WOResponse response = new WOResponse();
		response.setHeader( "text/plain; charset=utf-8", "content-type" );
		response.setContent( content );
		return response;
	}

	@Override
	public WOResponse dispatchRequest( final WORequest request ) {
		return _responder.apply( request ).generateResponse();
	}
}
