package com.webobjects.appserver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A deliberately dumb HTTP/1.1 client over a plain socket.
 *
 * The tests care about exact bytes - which header lines are sent, whether a body is chunked, request lines an HTTP client
 * library would normalise or refuse to send - so this writes what it is given and reads back what the server sends,
 * without interpreting more than it must. Requests are sent with "Connection: close" and read to EOF.
 */
public class RawHttp {

	private static final int TIMEOUT_MILLIS = 10_000;

	private final int _port;

	public RawHttp( final int port ) {
		_port = port;
	}

	public Response get( final String target ) {
		return send( "GET " + target + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n" );
	}

	/**
	 * Send a request with a body. Content-Length is added unless the given headers already say how the body is framed.
	 *
	 * @param headers Extra header lines, each terminated by CRLF
	 */
	public Response send( final String method, final String target, final String headers, final byte[] body ) {
		final StringBuilder head = new StringBuilder();
		head.append( method ).append( ' ' ).append( target ).append( " HTTP/1.1\r\n" );
		head.append( "Host: localhost\r\nConnection: close\r\n" );
		head.append( headers );

		final String lower = headers.toLowerCase( Locale.ROOT );

		if( !lower.contains( "content-length:" ) && !lower.contains( "transfer-encoding:" ) ) {
			head.append( "Content-Length: " ).append( body.length ).append( "\r\n" );
		}

		head.append( "\r\n" );

		final ByteArrayOutputStream request = new ByteArrayOutputStream();
		request.writeBytes( head.toString().getBytes( StandardCharsets.ISO_8859_1 ) );
		request.writeBytes( body );
		return send( request.toByteArray() );
	}

	public Response send( final String rawRequest ) {
		return send( rawRequest.getBytes( StandardCharsets.ISO_8859_1 ) );
	}

	public Response send( final byte[] rawRequest ) {
		try( Socket socket = new Socket( "localhost", _port )) {
			socket.setSoTimeout( TIMEOUT_MILLIS );
			final OutputStream out = socket.getOutputStream();
			out.write( rawRequest );
			out.flush();
			return Response.parse( socket.getInputStream().readAllBytes() );
		}
		catch( final IOException e ) {
			throw new RuntimeException( e );
		}
	}

	/**
	 * Open a request and leave it open, for responses that don't end by themselves (event streams)
	 */
	public OpenResponse open( final String target ) {
		try {
			final Socket socket = new Socket( "localhost", _port );
			socket.setSoTimeout( TIMEOUT_MILLIS );
			socket.getOutputStream().write( ("GET " + target + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes( StandardCharsets.ISO_8859_1 ) );
			socket.getOutputStream().flush();
			return new OpenResponse( socket );
		}
		catch( final IOException e ) {
			throw new RuntimeException( e );
		}
	}

	public record Header( String name, String value ) {}

	/**
	 * A complete response: status, header lines in the order they were sent (repeats kept), and the body - de-chunked if
	 * it was chunked, with {@link #chunked()} saying so.
	 */
	public record Response( int status, List<Header> headers, byte[] body, boolean chunked ) {

		static Response parse( final byte[] raw ) {
			final int headEnd = indexOf( raw, "\r\n\r\n".getBytes( StandardCharsets.ISO_8859_1 ), 0 );

			if( headEnd < 0 ) {
				throw new IllegalStateException( "No complete response head in " + raw.length + " bytes: " + new String( raw, StandardCharsets.ISO_8859_1 ) );
			}

			final String[] lines = new String( raw, 0, headEnd, StandardCharsets.ISO_8859_1 ).split( "\r\n" );
			final int status = Integer.parseInt( lines[0].split( " " )[1] );
			final List<Header> headers = new ArrayList<>();

			for( int i = 1; i < lines.length; i++ ) {
				final int colon = lines[i].indexOf( ':' );
				headers.add( new Header( lines[i].substring( 0, colon ).trim(), lines[i].substring( colon + 1 ).trim() ) );
			}

			byte[] body = java.util.Arrays.copyOfRange( raw, headEnd + 4, raw.length );
			final boolean chunked = headers.stream().anyMatch( h -> h.name().equalsIgnoreCase( "transfer-encoding" ) && h.value().equalsIgnoreCase( "chunked" ) );

			if( chunked ) {
				body = dechunk( body );
			}

			return new Response( status, headers, body, chunked );
		}

		/**
		 * @return The value of the first header with the given name, or null
		 */
		public String header( final String name ) {
			return headers.stream().filter( h -> h.name().equalsIgnoreCase( name ) ).map( Header::value ).findFirst().orElse( null );
		}

		/**
		 * @return The values of every header line with the given name, in order
		 */
		public List<String> headerLines( final String name ) {
			return headers.stream().filter( h -> h.name().equalsIgnoreCase( name ) ).map( Header::value ).toList();
		}

		public String bodyString() {
			return new String( body, StandardCharsets.UTF_8 );
		}
	}

	/**
	 * A response being read as it arrives
	 */
	public static class OpenResponse implements AutoCloseable {

		private final Socket _socket;
		private final InputStream _in;
		private final ByteArrayOutputStream _received = new ByteArrayOutputStream();

		OpenResponse( final Socket socket ) throws IOException {
			_socket = socket;
			_in = socket.getInputStream();
		}

		/**
		 * Read until the bytes received so far contain the given text, or the timeout passes.
		 *
		 * @return Everything received so far, as text
		 */
		public String readUntil( final String text, final long timeoutMillis ) {
			final long deadline = System.currentTimeMillis() + timeoutMillis;

			try {
				while( !received().contains( text ) && System.currentTimeMillis() < deadline ) {
					_socket.setSoTimeout( (int)Math.max( 1, deadline - System.currentTimeMillis() ) );
					final byte[] buffer = new byte[4096];
					final int n = _in.read( buffer );

					if( n < 0 ) {
						break;
					}

					_received.write( buffer, 0, n );
				}
			}
			catch( final java.net.SocketTimeoutException e ) {
				// Returning what we have; the caller asserts on it
			}
			catch( final IOException e ) {
				throw new RuntimeException( e );
			}

			return received();
		}

		/**
		 * Read until the server closes the connection.
		 *
		 * @return true if it did within the timeout
		 */
		public boolean readToEnd( final long timeoutMillis ) {
			final long deadline = System.currentTimeMillis() + timeoutMillis;

			try {
				while( System.currentTimeMillis() < deadline ) {
					_socket.setSoTimeout( (int)Math.max( 1, deadline - System.currentTimeMillis() ) );
					final byte[] buffer = new byte[4096];
					final int n = _in.read( buffer );

					if( n < 0 ) {
						return true;
					}

					_received.write( buffer, 0, n );
				}
			}
			catch( final java.net.SocketTimeoutException e ) {
				return false;
			}
			catch( final IOException e ) {
				return true;
			}

			return false;
		}

		public String received() {
			return _received.toString( StandardCharsets.UTF_8 );
		}

		@Override
		public void close() {
			try {
				_socket.close();
			}
			catch( final IOException e ) {
				// Closing is all we wanted
			}
		}
	}

	private static byte[] dechunk( final byte[] body ) {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		int pos = 0;

		while( pos < body.length ) {
			final int lineEnd = indexOf( body, "\r\n".getBytes( StandardCharsets.ISO_8859_1 ), pos );

			if( lineEnd < 0 ) {
				break;
			}

			final String sizeLine = new String( body, pos, lineEnd - pos, StandardCharsets.ISO_8859_1 ).split( ";" )[0].trim();
			final int size = Integer.parseInt( sizeLine, 16 );

			if( size == 0 ) {
				break;
			}

			out.write( body, lineEnd + 2, size );
			pos = lineEnd + 2 + size + 2;
		}

		return out.toByteArray();
	}

	private static int indexOf( final byte[] haystack, final byte[] needle, final int from ) {
		outer: for( int i = from; i <= haystack.length - needle.length; i++ ) {
			for( int j = 0; j < needle.length; j++ ) {
				if( haystack[i + j] != needle[j] ) {
					continue outer;
				}
			}
			return i;
		}
		return -1;
	}
}
