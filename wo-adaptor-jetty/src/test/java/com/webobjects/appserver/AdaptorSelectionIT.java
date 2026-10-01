package com.webobjects.appserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The adaptor selects itself: having the framework on the classpath is enough, and an explicit -WOAdaptor still wins.
 *
 * This happens while NSBundle loads the frameworks, from the framework's principal class, so it can only be observed in
 * a freshly launched JVM whose classpath carries the packaged framework jar - with the Resources/Info.plist that makes
 * NSBundle treat it as a framework. That needs the package phase, so it runs as an integration test:
 *
 * <pre>mvn verify -Pintegration</pre>
 */
public class AdaptorSelectionIT {

	private static final String JETTY = WOAdaptorJetty.class.getName();
	private static final String CLASSIC = "com.webobjects.appserver._private.WODefaultAdaptor";

	private static String classpath;

	@BeforeAll
	public static void buildClasspath() throws IOException {
		final String frameworkJar = System.getProperty( "frameworkJar" );
		assertTrue( frameworkJar != null && new File( frameworkJar ).isFile(), "the packaged framework jar, passed as frameworkJar: " + frameworkJar );

		try( JarFile jar = new JarFile( frameworkJar )) {
			assertTrue( jar.getEntry( "Resources/Info.plist" ) != null, "the jar is a framework bundle" );
		}

		// The framework jar in place of the module's loose classes, which carry no Info.plist and would shadow it
		final List<String> entries = new ArrayList<>();
		entries.add( frameworkJar );

		for( final String entry : System.getProperty( "java.class.path" ).split( File.pathSeparator ) ) {
			final Path path = Path.of( entry ).normalize();

			if( path.endsWith( Path.of( "target", "classes" ) ) || path.toString().equals( Path.of( frameworkJar ).normalize().toString() ) ) {
				continue;
			}

			entries.add( entry );
		}

		classpath = String.join( File.pathSeparator, entries );
	}

	@Test
	public void withNothingSetTheJettyAdaptorIsSelected() throws Exception {
		assertEquals( JETTY, selectedAdaptor() );
	}

	@Test
	public void namingTheJettyAdaptorSelectsIt() throws Exception {
		assertEquals( JETTY, selectedAdaptor( "-WOAdaptor", "WOAdaptorJetty" ) );
	}

	/**
	 * The default the framework installs is only a default: a launch argument naming another adaptor wins
	 */
	@Test
	public void anExplicitAdaptorStillWins() throws Exception {
		assertEquals( CLASSIC, selectedAdaptor( "-WOAdaptor", "WODefaultAdaptor" ) );
	}

	/**
	 * Launch the probe application with the given WO arguments and return the class of the adaptor it ended up with
	 */
	private static String selectedAdaptor( final String... woArguments ) throws Exception {
		// An empty home, so the developer's ~/WebObjects.properties can't decide the adaptor for us
		final Path home = Files.createTempDirectory( "wo-adaptor-jetty-it-home" );

		final List<String> command = new ArrayList<>();
		command.add( Path.of( System.getProperty( "java.home" ), "bin", "java" ).toString() );
		command.add( "-Duser.home=" + home );
		command.add( "-cp" );
		command.add( classpath );
		command.add( AdaptorSelectionProbe.class.getName() );
		command.add( "-WOPort" );
		command.add( String.valueOf( freePort() ) );
		command.addAll( Arrays.asList( woArguments ) );

		final Process process = new ProcessBuilder( command ).redirectErrorStream( true ).start();
		assertTrue( process.waitFor( 60, TimeUnit.SECONDS ), "the probe application exited" );

		final String output = new String( process.getInputStream().readAllBytes(), StandardCharsets.UTF_8 );

		return output.lines()
				.filter( line -> line.startsWith( AdaptorSelectionProbe.MARKER ) )
				.map( line -> line.substring( AdaptorSelectionProbe.MARKER.length() ) )
				.findFirst()
				.orElseThrow( () -> new AssertionError( "The probe reported no adaptor. Its output:\n" + output.lines().collect( Collectors.joining( "\n" ) ) ) );
	}

	private static int freePort() throws IOException {
		try( ServerSocket socket = new ServerSocket( 0 )) {
			return socket.getLocalPort();
		}
	}
}
