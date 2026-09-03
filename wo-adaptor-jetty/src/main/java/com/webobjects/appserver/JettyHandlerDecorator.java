package com.webobjects.appserver;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;

/**
 * Extension point for adding Jetty handlers around the request handler of the server WOAdaptorJetty builds by default.
 *
 * Implementations are discovered with java.util.ServiceLoader: list the implementing class in
 * META-INF/services/com.webobjects.appserver.JettyHandlerDecorator and it is picked up automatically, so having a jar
 * that provides a decorator on the classpath is all it takes to enable it (wo-adaptor-jetty-websocket works this way).
 *
 * Decorators are applied as the OUTERMOST layers of the handler chain, after the WO request handler and any QoS
 * backpressure handler have been assembled. A decorator therefore sees every request first and may intercept some
 * of them (a WebSocket upgrade, say) while passing the rest on to the handler it was given. When several decorators
 * are present, the order in which they are applied is unspecified.
 */
public interface JettyHandlerDecorator {

	/**
	 * @param server The Jetty server being assembled (not yet started)
	 * @param inner The handler to wrap. The returned handler must delegate to it for every request it does not handle itself
	 * @return The handler that replaces {@code inner} in the chain
	 */
	Handler decorate( Server server, Handler inner );
}
