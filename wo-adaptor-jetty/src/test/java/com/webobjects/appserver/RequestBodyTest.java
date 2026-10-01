package com.webobjects.appserver;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Request bodies arrive intact, through WebObjects' own multipart parsing, and a body whose length is unknown is
 * refused rather than silently dropped.
 */
public class RequestBodyTest {

	private static final String BOUNDARY = "----wo-adaptor-jetty-test-boundary";

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
	public void plainBodyArrivesByteForByte() {
		final byte[] body = random( 50_000, 1 );

		TestApplication.respondWith( request -> TestApplication.text( sha256( request.content().bytes() ) ) );

		final RawHttp.Response response = server.http().send( "POST", "/upload", "Content-Type: application/octet-stream\r\n", body );
		assertEquals( 200, response.status() );
		assertEquals( sha256( body ), response.bodyString() );
	}

	/**
	 * Multipart uploads read through WOMultipartIterator, in sizes that straddle the chunk boundaries of Jetty's request
	 * stream. The adaptor hands WO that stream without a buffering layer, so WO's multipart reader sees short reads and has
	 * to cope with a boundary split across two of them.
	 */
	@ParameterizedTest
	@ValueSource(ints = { 1, 1023, 1024, 1025, 8191, 8192, 8193, 16383, 16384, 16385, 100_000, 1_048_576 })
	public void multipartUploadArrivesByteForByte( final int size ) {
		final byte[] file = random( size, size );
		assertEquals( sha256( file ), uploadAndHash( file ) );
	}

	/**
	 * A payload peppered with byte sequences that look like the start of the multipart separator, the worst case for a
	 * boundary scanner fed short reads
	 */
	@Test
	public void multipartPayloadResemblingTheBoundary() {
		final ByteArrayOutputStream payload = new ByteArrayOutputStream();
		final Random random = new Random( 42 );

		for( int i = 0; i < 2_000; i++ ) {
			final byte[] noise = new byte[7];
			random.nextBytes( noise );
			payload.writeBytes( noise );
			payload.writeBytes( "\r\n--".getBytes( StandardCharsets.ISO_8859_1 ) );
			payload.writeBytes( "----------------".getBytes( StandardCharsets.ISO_8859_1 ) );
		}

		final byte[] file = payload.toByteArray();
		assertEquals( sha256( file ), uploadAndHash( file ) );
	}

	/**
	 * WebObjects has to know a body's length up front. A chunked body has none, and the classic adaptor silently treats
	 * it as empty; this adaptor says so instead.
	 */
	@Test
	public void chunkedBodyIsRefusedWith411() {
		TestApplication.respondWith( TestApplication::echo );

		final String request = "POST /upload HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n";
		assertEquals( 411, server.http().send( request ).status() );
	}

	/**
	 * A request with no body also has no length. It must not be mistaken for a chunked one.
	 */
	@Test
	public void bodylessRequestsAreNotRefused() {
		TestApplication.respondWith( TestApplication::echo );

		assertEquals( 200, server.http().get( "/" ).status() );
		assertEquals( 200, server.http().send( "POST /empty HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\nContent-Length: 0\r\n\r\n" ).status() );
	}

	/**
	 * Upload a file as one multipart part and return the SHA-256 of what WO's multipart iterator produced for it
	 */
	private static String uploadAndHash( final byte[] file ) {
		TestApplication.respondWith( request -> {
			final WOMultipartIterator iterator = request.multipartIterator();
			assertTrue( iterator != null, "the request was recognised as multipart" );

			try {
				WOMultipartIterator.WOFormData part;

				while( (part = iterator.nextFormData()) != null ) {
					if( part.isFileUpload() ) {
						// A small read buffer forces many short reads against the stream
						try( InputStream in = part.formDataInputStream()) {
							final MessageDigest digest = MessageDigest.getInstance( "SHA-256" );
							final byte[] buffer = new byte[1024];
							int read;

							while( (read = in.read( buffer )) != -1 ) {
								digest.update( buffer, 0, read );
							}

							return TestApplication.text( HexFormat.of().formatHex( digest.digest() ) );
						}
					}
				}
			}
			catch( final Exception e ) {
				throw new RuntimeException( e );
			}

			return TestApplication.text( "no file part" );
		} );

		final ByteArrayOutputStream body = new ByteArrayOutputStream();
		body.writeBytes( ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"note\"\r\n\r\nsome text\r\n").getBytes( StandardCharsets.ISO_8859_1 ) );
		body.writeBytes( ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"f.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes( StandardCharsets.ISO_8859_1 ) );
		body.writeBytes( file );
		body.writeBytes( ("\r\n--" + BOUNDARY + "--\r\n").getBytes( StandardCharsets.ISO_8859_1 ) );

		final RawHttp.Response response = server.http().send( "POST", "/upload", "Content-Type: multipart/form-data; boundary=" + BOUNDARY + "\r\n", body.toByteArray() );
		assertEquals( 200, response.status() );
		return response.bodyString();
	}

	private static byte[] random( final int size, final long seed ) {
		final byte[] bytes = new byte[size];
		new Random( seed ).nextBytes( bytes );
		return bytes;
	}

	private static String sha256( final byte[] bytes ) {
		try {
			return HexFormat.of().formatHex( MessageDigest.getInstance( "SHA-256" ).digest( bytes ) );
		}
		catch( final Exception e ) {
			throw new RuntimeException( e );
		}
	}
}
