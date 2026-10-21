package io.github.supersanchayrx.redis.it;

import io.github.supersanchayrx.redis.Config;
import io.github.supersanchayrx.redis.Server;

/** A real server on a random free port, its loop on a background thread. */
final class TestServer implements AutoCloseable {

    final Server server;
    final Thread thread;
    final int port;

    TestServer(Config config) throws Exception {
        config.port = 0;
        config.bind = "127.0.0.1";
        server = new Server(config);
        server.loadAof();
        server.bind();
        port = server.port();
        thread = new Thread(server::run, "loop-" + port);
        thread.setDaemon(true);
        thread.start();
    }

    TestServer() throws Exception {
        this(new Config());
    }

    RespClient client() throws Exception {
        return new RespClient("127.0.0.1", port);
    }

    @Override
    public void close() throws Exception {
        server.stop();
        thread.join(5000);
    }
}
