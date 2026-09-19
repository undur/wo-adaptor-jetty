package com.webobjects.appserver;

import com.webobjects.foundation.NSProperties;

/**
 * The framework's principal class: NSBundle loads and initializes it while bundles are being loaded, which is our one
 * chance to influence which WOAdaptor the application ends up using.
 *
 * Having this adaptor on the classpath is taken to mean you want to use it, so we select it here rather than making every
 * application pass {@code -WOAdaptor WOAdaptorJetty}.
 *
 * The timing is delicate, and worth writing down. WOApplication resolves the adaptor's name from
 * {@code WOProperties.ThePrimaryAdaptorName}, reading the {@code WOAdaptor} property only when that field is still null.
 * Setting the field here therefore decides the adaptor - but at bundle-loading time the property is not visible yet: the
 * {@code WebObjects.properties} files have been read, while the launch arguments an application was started with have not,
 * since WO pushes those into NSProperties (NSProperties.setPropertiesFromArgv) later in its startup. So a naive check of
 * the property here sees null even when the user passed {@code -WOAdaptor SomethingElse}, and claiming the field would
 * silently override their choice.
 *
 * We therefore do not claim the field. We install the default the other way around: the property is set only if nothing
 * has set it, which leaves both a properties-file entry and a launch argument free to win, since a launch argument
 * overwrites the property when WO parses it.
 */
public class WOAdaptorJettyPrincipal {

	/**
	 * WO's "which adaptor" property. Spelled out rather than taken from WOProperties._AdaptorKey: that class has not
	 * necessarily been initialized when a principal class runs, and reading the constant too early yields null - which
	 * silently files the value under a null key instead of setting the property.
	 */
	private static final String ADAPTOR_KEY = "WOAdaptor";

	static {
		selectAdaptor();
	}

	/**
	 * Name the Jetty adaptor as the application's adaptor, unless a choice has already been recorded.
	 */
	private static void selectAdaptor() {

		// Somebody has already decided - a properties file, or an earlier caller. Leave their choice alone.
		if( NSProperties.stringForKey( ADAPTOR_KEY ) != null ) {
			return;
		}

		// The fully qualified name, so this keeps working when the adaptor moves out of com.webobjects.appserver: WO only
		// resolves a short name for classes in its own packages or in a loaded bundle.
		//
		// Written as the property rather than straight into ThePrimaryAdaptorName, because the launch arguments have not
		// been parsed yet: they land in NSProperties afterwards and overwrite this, which is exactly what we want, so that
		// -WOAdaptor on the command line still wins over the default we are installing here.
		NSProperties._setProperty( ADAPTOR_KEY, WOAdaptorJetty.class.getName() );
	}
}
