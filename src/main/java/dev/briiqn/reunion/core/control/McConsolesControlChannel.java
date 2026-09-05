/*
 * Copyright (C) 2026 Briiqn
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.briiqn.reunion.core.control;

import com.alibaba.fastjson2.JSONObject;
import dev.briiqn.reunion.core.ReunionServer;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.log4j.Log4j2;

/**
 * MinecraftConsoles fork - control channel to the game process.
 *
 * <p>When the proxy is launched by Minecraft.Client (see Minecraft.Client/Windows64/ProxyHost.h)
 * it is given {@code --mcc-control-port=N}. The GAME is the listener and the proxy dials back,
 * which is what removes the startup race: the port is already bound before the JVM is spawned, so
 * neither side ever has to guess or retry.
 *
 * <p>Framing is deliberately the same as the LCE game protocol - 4-byte big-endian length, then a
 * UTF-8 JSON object - so there is one wire convention in this bridge, not two.
 *
 * <p>This is a message bus in shape (named topics, one typed message per frame) but not in
 * machinery: two processes on one machine over loopback are ordered and reliable already, so a
 * real broker would add a dependency and a failure mode for nothing.
 *
 * <p>Absent the flag, nothing here runs and the proxy behaves exactly as upstream does - which is
 * what keeps it usable standalone.
 */
@Log4j2
public final class McConsolesControlChannel {

  private static final String ARG = "--mcc-control-port=";

  private static volatile McConsolesControlChannel instance;

  private final Socket socket;
  private final DataInputStream in;
  private final DataOutputStream out;
  private final ReunionServer server;
  private final AtomicInteger seq = new AtomicInteger();

  private McConsolesControlChannel(Socket socket, ReunionServer server) throws IOException {
    this.socket = socket;
    this.server = server;
    this.in = new DataInputStream(socket.getInputStream());
    this.out = new DataOutputStream(socket.getOutputStream());
  }

  /** Returns the control port from the command line, or -1 when the game did not launch us. */
  public static int parsePort(String[] args) {
    for (String a : args) {
      if (a.startsWith(ARG)) {
        try {
          return Integer.parseInt(a.substring(ARG.length()).trim());
        } catch (NumberFormatException e) {
          log.error("[Control] bad {}: '{}'", ARG, a);
          return -1;
        }
      }
    }
    return -1;
  }

  /**
   * Connects back to the game and starts the reader. Failure is logged and swallowed: a proxy that
   * cannot reach the game is still a working standalone proxy, and killing it here would turn a
   * cosmetic problem into a failure to start.
   */
  public static void connect(int port, ReunionServer server) {
    Thread.ofVirtual().name("mcc-control").start(() -> {
      try {
        Socket s = new Socket(InetAddress.getLoopbackAddress(), port);
        s.setTcpNoDelay(true);
        McConsolesControlChannel ch = new McConsolesControlChannel(s, server);
        instance = ch;
        log.info("[Control] connected to the game on port {}", port);
        ch.sendReady();
        ch.readLoop();
      } catch (IOException e) {
        log.warn("[Control] could not reach the game on port {}: {}", port, e.getMessage());
      }
    });
  }

  public static void status(String state, String detail) {
    McConsolesControlChannel ch = instance;
    if (ch != null) {
      JSONObject d = new JSONObject();
      d.put("state", state);
      d.put("detail", detail == null ? "" : detail);
      ch.send("proxy.status", d);
    }
  }

  public static void logToGame(String level, String msg) {
    McConsolesControlChannel ch = instance;
    if (ch != null) {
      JSONObject d = new JSONObject();
      d.put("level", level);
      d.put("msg", msg);
      ch.send("proxy.log", d);
    }
  }

  private void sendReady() {
    JSONObject d = new JSONObject();
    d.put("listenPort", server.getConfig().getConnection().getListenPort());
    d.put("version", "reunion-mcc");
    send("proxy.ready", d);
  }

  private void send(String topic, JSONObject data) {
    JSONObject msg = new JSONObject();
    msg.put("topic", topic);
    msg.put("seq", seq.incrementAndGet());
    msg.put("data", data);

    byte[] payload = msg.toString().getBytes(StandardCharsets.UTF_8);
    try {
      synchronized (out) {
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
      }
    } catch (IOException e) {
      log.warn("[Control] send failed ({}): {}", topic, e.getMessage());
    }
  }

  private void readLoop() {
    try {
      while (!socket.isClosed()) {
        int len = in.readInt();
        // A control message is a small flat object. A huge length means the stream is out of sync
        // and going on would allocate whatever the corrupt value says.
        if (len <= 0 || len > (1 << 20)) {
          log.error("[Control] bad frame length {} - dropping channel", len);
          break;
        }
        byte[] buf = new byte[len];
        in.readFully(buf);
        handle(JSONObject.parseObject(new String(buf, StandardCharsets.UTF_8)));
      }
    } catch (Exception e) {
      log.info("[Control] channel closed: {}", e.getMessage());
    } finally {
      instance = null;
    }
  }

  private void handle(JSONObject msg) {
    String topic = msg.getString("topic");
    JSONObject data = msg.getJSONObject("data");
    if (topic == null) {
      return;
    }

    switch (topic) {
      case "server.select" -> {
        if (data == null) {
          return;
        }
        String host = data.getString("host");
        Integer port = data.getInteger("port");
        if (host == null || host.isEmpty() || port == null) {
          log.warn("[Control] server.select with no host/port - ignored");
          return;
        }
        // Applies to the NEXT Java connection: sessions already bridged keep the server they
        // joined. That is deliberate - silently moving a player mid-session would look like a
        // random disconnect.
        server.getConfig().getConnection().setJavaHost(host);
        server.getConfig().getConnection().setJavaPort(port);
        log.info("[Control] target Java server set to {}:{}", host, port);
        status("configured", host + ":" + port);
      }
      case "proxy.shutdown" -> {
        log.info("[Control] shutdown requested by the game");
        // The game's Job Object will kill us regardless; exiting here is the clean path that lets
        // sessions close and the config flush first.
        new Thread(() -> {
          try {
            server.stop();
          } catch (Exception ignored) {
            // stop() is best-effort - we are on our way out either way.
          }
          System.exit(0);
        }, "mcc-control-shutdown").start();
      }
      default -> log.debug("[Control] unknown topic '{}'", topic);
    }
  }
}
