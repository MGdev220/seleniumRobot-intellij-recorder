package io.github.bhecquet.seleniumRobot.recorder;


import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;

public class JettyService implements Disposable {

    private static final Logger LOG = Logger.getInstance(JettyService.class);
    private Server server;

    public JettyService() {
        try {
            server = new Server(5222); // Port fixe pour Chrome Recorder

            ServletContextHandler handler = new ServletContextHandler(ServletContextHandler.SESSIONS);
            handler.setContextPath("/");
            server.setHandler(handler);

            handler.addServlet(SeleniumServlet.class, "/event");

            server.start();
            LOG.info("Jetty started on port 5222");

        } catch (Exception e) {
            LOG.error("Failed to start Jetty", e);
        }
    }

    @Override
    public void dispose() {
        try {
            if (server != null && server.isRunning()) {
                server.stop();
                LOG.info("Jetty stopped");
            }
        } catch (Exception e) {
            LOG.error("Error stopping Jetty", e);
        }
    }
}
