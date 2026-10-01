package com.webobjects.appserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A property that is set but not a number is an error, not "unset" (#11)
 */
public class JettyAdaptorPropertiesTest {

	@BeforeAll
	public static void properties() {
		TestApplication.instance();
		System.setProperty( "TestUnparseable", "5O" );
		System.setProperty( "TestPadded", " 12 " );
		System.setProperty( "TestBlank", "  " );
	}

	@Test
	public void unsetOrBlankGivesTheDefault() {
		assertEquals( 7, JettyAdaptorProperties.integer( "TestNotSetAnywhere", 7 ) );
		assertEquals( 7, JettyAdaptorProperties.integer( "TestBlank", 7 ) );
	}

	@Test
	public void surroundingWhitespaceIsIgnored() {
		assertEquals( 12, JettyAdaptorProperties.integer( "TestPadded", 0 ) );
	}

	@Test
	public void anUnparseableValueIsAnErrorNamingTheProperty() {
		final IllegalArgumentException e = assertThrows( IllegalArgumentException.class, () -> JettyAdaptorProperties.integer( "TestUnparseable", 0 ) );
		assertTrue( e.getMessage().contains( "TestUnparseable" ) && e.getMessage().contains( "5O" ), e.getMessage() );
	}
}
