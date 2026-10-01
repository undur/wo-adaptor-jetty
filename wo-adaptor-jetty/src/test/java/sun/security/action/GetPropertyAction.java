package sun.security.action;

import java.security.PrivilegedAction;

/**
 * Test-only copy of wonder-slim's replacement for a Security Manager JDK class removed in JDK 24.
 *
 * WebObjects' NSTimeZone calls it while a WOApplication is being constructed, so the test harness cannot construct one
 * without it. Applications get it from wonder-slim's ERExtensions; the adaptor does not depend on that, so the tests
 * carry their own.
 *
 * Originally:
 * The class is used by at least NSTimeZone, which causes a WO app to fail on startup if this class is not present.
 */

public class GetPropertyAction implements PrivilegedAction<String> {

	private final String _propertyName;

	/**
	 * Constructor taking the name of a system property to get the value of
	 */
	public GetPropertyAction( String propertyName ) {
		this._propertyName = propertyName;
	}

	/**
	 * @return The value of the property this object represents/wraps
	 */
	@Override
	public String run() {
		return System.getProperty( _propertyName );
	}
}