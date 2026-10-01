package com.webobjects.appserver;

/**
 * Launched in a separate JVM by {@link AdaptorSelectionIT}: starts a WebObjects application through WOApplication.main(),
 * as a deployed application starts, prints the class of the adaptor WO built, and exits.
 *
 * WO constructs its adaptors inside WOApplication's constructor, so by the time this subclass's constructor body runs the
 * decision has been made - which is also the earliest any application code can observe it.
 */
public class AdaptorSelectionProbe extends WOApplication {

	public static final String MARKER = "SELECTED-ADAPTOR=";

	public AdaptorSelectionProbe() {
		super();
		System.out.println( MARKER + adaptors().objectAtIndex( 0 ).getClass().getName() );
		System.out.flush();
		Runtime.getRuntime().halt( 0 );
	}

	public static void main( final String[] args ) {
		WOApplication.main( args, AdaptorSelectionProbe.class );
	}
}
