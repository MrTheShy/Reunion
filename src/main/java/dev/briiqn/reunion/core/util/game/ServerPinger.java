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
package dev.briiqn.reunion.core.util.game;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import dev.briiqn.reunion.core.util.VarIntUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.log4j.Log4j2;

@Log4j2
public final class ServerPinger {

  private static final int TIMEOUT_MS = 5_000;
  private static final Map<String, ProtocolVersion> CACHE = new ConcurrentHashMap<>();

  private ServerPinger() {
  }

  /**
   * MinecraftConsoles fork - everything the server list needs from one status ping.
   *
   * <p>{@code reachable} is the field that decides whether an address is a Java server at all: a
   * host that completes the status exchange is one by definition, which is what lets the game
   * classify a saved entry without asking the player to tick a box.
   */
  public record Status(boolean reachable, String motd, int players, int maxPlayers,
                       String versionName, int protocol, int latencyMs, String error) {

    public static Status unreachable(String error) {
      return new Status(false, "", 0, 0, "", -1, -1, error == null ? "" : error);
    }
  }

  public static ProtocolVersion detect(String host, int port) {
    String key = host.toLowerCase() + ":" + port;
    ProtocolVersion cached = CACHE.get(key);
    if (cached != null) {
      return cached;
    }

    Status st = status(host, port);
    if (!st.reachable() || st.protocol() < 0) {
      return null;
    }

    ProtocolVersion pv = ProtocolVersion.getProtocol(st.protocol());
    if (pv == null || pv == ProtocolVersion.unknown) {
      log.warn("[ServerPinger] Unknown protocol {} from {}:{}", st.protocol(), host, port);
      return null;
    }

    log.debug("[ServerPinger] {}:{} reported {}", host, port, pv.getName());
    CACHE.put(key, pv);
    return pv;
  }

  /**
   * Performs a Java Edition status ping and returns the whole answer.
   *
   * <p>SRV is resolved here for the same reason it is resolved before a join: an address that only
   * publishes its endpoint through SRV would otherwise be reported offline while being perfectly
   * up, and the player would have no way to tell those two states apart.
   */
  public static Status status(String host, int port) {
    SrvResolver.Endpoint ep = SrvResolver.resolve(host, port, port != 25565 && port != 0);
    return statusDirect(ep.connectHost(), ep.connectPort(), ep.handshakeHost(),
        ep.handshakePort());
  }

  private static Status statusDirect(String connectHost, int connectPort, String handshakeHost,
      int handshakePort) {
    long started = System.currentTimeMillis();
    try (Socket socket = new Socket()) {
      socket.setTcpNoDelay(true);
      socket.setSoTimeout(TIMEOUT_MS);
      socket.connect(new InetSocketAddress(connectHost, connectPort), TIMEOUT_MS);

      OutputStream out = socket.getOutputStream();
      DataInputStream in = new DataInputStream(socket.getInputStream());

      ByteBuf handshake = Unpooled.buffer();
      VarIntUtil.write(handshake, 0x00);
      VarIntUtil.write(handshake, ProtocolVersion.v26_1.getVersion());
      writeString(handshake, handshakeHost);
      handshake.writeShort(handshakePort);
      VarIntUtil.write(handshake, 1);
      writeFramedPacket(out, handshake);

      ByteBuf statusRequest = Unpooled.buffer();
      VarIntUtil.write(statusRequest, 0x00);
      writeFramedPacket(out, statusRequest);
      out.flush();

      int size = read(in);
      // A status response is a JSON document, not a stream: a length no sane server would send
      // means the peer is not speaking this protocol, and allocating what it asked for would be
      // taking a hostile number at its word.
      if (size <= 0 || size > (4 << 20)) {
        return Status.unreachable("bad status frame length " + size);
      }
      byte[] data = new byte[size];
      in.readFully(data);
      int latency = (int) (System.currentTimeMillis() - started);

      ByteBuf responseBuf = Unpooled.wrappedBuffer(data);
      int packetId = VarIntUtil.read(responseBuf);
      if (packetId != 0x00) {
        return Status.unreachable("unexpected packet id " + packetId);
      }

      JSONObject root = JSON.parseObject(readString(responseBuf));
      if (root == null) {
        return Status.unreachable("unparsable status response");
      }

      JSONObject version = root.getJSONObject("version");
      String versionName = version != null ? version.getString("name") : "";
      int protocol = version != null ? version.getIntValue("protocol", -1) : -1;

      JSONObject players = root.getJSONObject("players");
      int online = players != null ? players.getIntValue("online") : 0;
      int max = players != null ? players.getIntValue("max") : 0;

      return new Status(true, flattenDescription(root.get("description")), online, max,
          versionName == null ? "" : versionName, protocol, latency, "");

    } catch (IOException e) {
      log.debug("[ServerPinger] Failed to ping {}:{}  {}", connectHost, connectPort,
          e.getMessage());
      return Status.unreachable(e.getMessage());
    } catch (RuntimeException e) {
      log.debug("[ServerPinger] Bad status from {}:{}  {}", connectHost, connectPort,
          e.toString());
      return Status.unreachable(e.toString());
    }
  }

  /**
   * Reduces a MOTD to plain text.
   *
   * <p>The description field is a chat component, which over the years has been a bare string, an
   * object with a text field, and an object with a nested extra array - servers still send all
   * three. Section-sign colour codes are stripped because the destination is an LCE menu row that
   * would render them as literal characters.
   */
  private static String flattenDescription(Object description) {
    StringBuilder sb = new StringBuilder();
    appendComponent(description, sb, 0);
    String flat = sb.toString().replaceAll("§.", "").replace('\n', ' ').trim();
    return flat.length() > 200 ? flat.substring(0, 200) : flat;
  }

  private static void appendComponent(Object node, StringBuilder sb, int depth) {
    // Components nest, and nothing stops a server sending a pathological one. The limit is well
    // past any real MOTD and keeps a malformed reply from costing us the stack.
    if (node == null || depth > 16) {
      return;
    }
    if (node instanceof String s) {
      sb.append(s);
      return;
    }
    if (node instanceof JSONObject obj) {
      String text = obj.getString("text");
      if (text != null) {
        sb.append(text);
      }
      Object extra = obj.get("extra");
      if (extra != null) {
        appendComponent(extra, sb, depth + 1);
      }
      return;
    }
    if (node instanceof JSONArray arr) {
      for (Object child : arr) {
        appendComponent(child, sb, depth + 1);
      }
    }
  }

  public static int ping(String host, int port) {
    ProtocolVersion pv = detect(host, port);
    return pv != null ? pv.getVersion() : -1;
  }

  public static void invalidate(String host, int port) {
    CACHE.remove(host.toLowerCase() + ":" + port);
  }

  public static void clear() {
    CACHE.clear();
  }

  private static void writeFramedPacket(OutputStream out, ByteBuf packet) throws IOException {
    ByteBuf frame = Unpooled.buffer();
    try {
      VarIntUtil.write(frame, packet.readableBytes());
      byte[] lengthBytes = new byte[frame.readableBytes()];
      frame.readBytes(lengthBytes);
      out.write(lengthBytes);

      byte[] data = new byte[packet.readableBytes()];
      packet.readBytes(data);
      out.write(data);
    } finally {
      frame.release();
      packet.release();
    }
  }

  private static int read(DataInputStream in) throws IOException {
    ByteBuf buf = Unpooled.buffer(5);
    try {
      byte b;
      int count = 0;
      do {
        b = in.readByte();
        buf.writeByte(b);
        // A VarInt is five bytes at most. Without this a peer that answers with a stream of
        // continuation bytes would grow the buffer until the JVM gave up.
        if (++count > 5) {
          throw new IOException("VarInt length prefix longer than 5 bytes");
        }
      } while ((b & 0x80) != 0);
      return VarIntUtil.read(buf);
    } finally {
      buf.release();
    }
  }

  private static void writeString(ByteBuf buf, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    VarIntUtil.write(buf, bytes.length);
    buf.writeBytes(bytes);
  }

  private static String readString(ByteBuf buf) {
    int len = VarIntUtil.read(buf);
    byte[] bytes = new byte[len];
    buf.readBytes(bytes);
    return new String(bytes, StandardCharsets.UTF_8);
  }
}
