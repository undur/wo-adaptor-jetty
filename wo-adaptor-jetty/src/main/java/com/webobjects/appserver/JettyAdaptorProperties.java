package com.webobjects.appserver;

import com.webobjects.foundation.NSProperties;

/**
 * Reads the adaptor's configuration properties.
 *
 * NSProperties.integerForKey() answers 0 for a value it cannot parse, which is indistinguishable from the property not
 * being set. For most of the adaptor's properties 0 means "unset" - no concurrency limit, the default timeout - so a typo
 * such as {@code -DJettyMaxConcurrentRequests=5O} silently turned a feature off while the operator believed it was on.
 * Here a value that is present but not a number is an error, naming the property and the value.
 *
 * Used by the adaptor and its push module, which is why it is public; it is not meant for applications.
 */
public final class JettyAdaptorProperties {

	private JettyAdaptorProperties() {}

	/**
	 * @return The property's value as an integer, or {@code defaultValue} if the property is not set or is blank
	 * @throws IllegalArgumentException if the property is set to something that is not an integer
	 */
	public static int integer( final String key, final int defaultValue ) {
		final String value = NSProperties.stringForKey( key );

		if( value == null || value.isBlank() ) {
			return defaultValue;
		}

		try {
			return Integer.parseInt( value.strip() );
		}
		catch( final NumberFormatException e ) {
			throw new IllegalArgumentException( "The property %s is set to '%s', which is not an integer".formatted( key, value ), e );
		}
	}
}
