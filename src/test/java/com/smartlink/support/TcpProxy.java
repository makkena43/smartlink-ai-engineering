package com.smartlink.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A TCP proxy that can be cut and restored, used to take the database away and give it back.
 *
 * <p>Existing tests cover an outage by pointing the application at a port nothing listens on. That
 * proves the DOWN half and can never prove the other half, because the outage has no end: a dead
 * port stays dead for the life of the context. Recovery is the half operators actually care about —
 * an instance that goes DOWN correctly and never comes back needs a human at 3am.
 *
 * <p>Stopping and restarting the container would work, except that a restarted container gets a new
 * mapped port, so the application's configured URL would no longer point at it. Proxying keeps the
 * address the application was configured with stable across the outage, which is what makes "it
 * recovered on its own" a meaningful claim.
 *
 * <p>{@link #cut()} closes every live connection and closes new ones on arrival, rather than
 * closing the listening socket. The application sees connections fail, which is the symptom under
 * test, and the port stays reserved so nothing else can take it before {@link #restore()}.
 */
public final class TcpProxy implements AutoCloseable {

  private final ServerSocket listener;
  private final String targetHost;
  private final int targetPort;
  private final ExecutorService threads = Executors.newCachedThreadPool();
  private final List<Socket> live = new CopyOnWriteArrayList<>();

  private volatile boolean forwarding = true;
  private volatile boolean running = true;

  public TcpProxy(String targetHost, int targetPort) throws IOException {
    this.targetHost = targetHost;
    this.targetPort = targetPort;
    this.listener = new ServerSocket(0);
    threads.submit(this::acceptLoop);
  }

  public int port() {
    return listener.getLocalPort();
  }

  /** Breaks the connection to the target, as an unreachable database would. */
  public void cut() {
    forwarding = false;
    for (Socket socket : live) {
      closeQuietly(socket);
    }
    live.clear();
  }

  /** Lets connections through again. */
  public void restore() {
    forwarding = true;
  }

  private void acceptLoop() {
    while (running) {
      try {
        Socket inbound = listener.accept();
        if (!forwarding) {
          closeQuietly(inbound);
          continue;
        }
        threads.submit(() -> connect(inbound));
      } catch (IOException e) {
        if (running) {
          // Accept failures while running are worth surfacing; on shutdown they are expected.
          return;
        }
      }
    }
  }

  private void connect(Socket inbound) {
    Socket outbound = new Socket();
    try {
      outbound.connect(new InetSocketAddress(targetHost, targetPort), 2_000);
      live.add(inbound);
      live.add(outbound);
      threads.submit(() -> pump(inbound, outbound));
      pump(outbound, inbound);
    } catch (IOException e) {
      closeQuietly(inbound);
      closeQuietly(outbound);
    }
  }

  private void pump(Socket from, Socket to) {
    byte[] buffer = new byte[8192];
    try (InputStream in = from.getInputStream();
        OutputStream out = to.getOutputStream()) {
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
        out.flush();
      }
    } catch (IOException e) {
      // A cut connection lands here. That is the fault being injected, not an error.
    } finally {
      closeQuietly(from);
      closeQuietly(to);
    }
  }

  private static void closeQuietly(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // Nothing useful to do while tearing a connection down on purpose.
    }
  }

  @Override
  public void close() {
    running = false;
    cut();
    try {
      listener.close();
    } catch (IOException ignored) {
      // Shutting down.
    }
    threads.shutdownNow();
  }
}
