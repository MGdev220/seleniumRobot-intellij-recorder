package io.github.bhecquet.seleniumRobot.recorder;

import com.intellij.openapi.project.Project;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

public class SeleniumActionServer {

    private static Object lock = new Object();
    private static Server server;

    public static boolean isStarted() {
        synchronized (lock) {
            return server != null;
        }
    }

    public static void startervletServer(Project project, int port) throws Exception {

        synchronized (lock) {
            if (server == null) {
                try {
                    server = new Server();

                    ServerConnector connector = new ServerConnector(server);
                    connector.setPort(port);
                    server.setConnectors(new Connector[]{connector});

                    ServletContextHandler context = new ServletContextHandler();
                    context.setContextPath("/");
                    SeleniumServlet seleniumServlet = new SeleniumServlet(project);
                    ServletHolder seleniumServletHolder = new ServletHolder(seleniumServlet);
                    context.addServlet(seleniumServletHolder, "/");

                    server.setHandler(context);

                    server.start();
                } catch (Exception e) {
                    server = null;
                    throw e;
                }
            }
        }
    }

    public static void stopServletServer() {
        synchronized (lock) {
            if (server != null) {
                try {
                    server.stop();
                } catch (Exception e) {
                }
                server = null;
            }
        }
    }


}
