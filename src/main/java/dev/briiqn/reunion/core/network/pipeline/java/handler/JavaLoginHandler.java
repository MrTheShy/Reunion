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

package dev.briiqn.reunion.core.network.pipeline.java.handler;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import dev.briiqn.reunion.core.ReunionServer;
import dev.briiqn.reunion.core.data.ForwardingMode;
import dev.briiqn.reunion.core.network.packet.data.RawPacket;
import dev.briiqn.reunion.core.network.pipeline.java.decode.JavaCipherDecoder;
import dev.briiqn.reunion.core.network.pipeline.java.encode.JavaCipherEncoder;
import dev.briiqn.reunion.core.network.packet.manager.PacketManager;
import dev.briiqn.reunion.core.network.packet.protocol.console.s2c.impl.ConsoleAuthResultS2CPacket;
import dev.briiqn.reunion.core.network.packet.protocol.console.s2c.impl.ConsoleAuthSchemeS2CPacket;
import dev.briiqn.reunion.core.session.ConsoleSession;
import dev.briiqn.reunion.core.session.JavaSession;
import dev.briiqn.reunion.core.util.StringUtil;
import dev.briiqn.reunion.core.util.VarIntUtil;
import dev.briiqn.reunion.core.util.auth.AuthUtil;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.log4j.Log4j2;
import net.raphimc.minecraftauth.java.JavaAuthManager;

@Log4j2
public final class JavaLoginHandler {

  private static final String VELOCITY_CHANNEL = "velocity:player_info";

  private final JavaSession session;
  private final ConsoleSession cs;
  private final ReunionServer server;

  public JavaLoginHandler(JavaSession session) {
    this.session = session;
    this.cs = session.getConsoleSession();
    this.server = session.getServer();
  }

  private static void writeString(ByteBuf buf, String s) {
    byte[] b = s.getBytes(StandardCharsets.UTF_8);
    VarIntUtil.write(buf, b.length);
    buf.writeBytes(b);
  }

  void handle(ChannelHandlerContext ctx, RawPacket raw) {
    switch (raw.id()) {
      case 0x01 -> handleEncryptionRequest(ctx, raw.payload());
      case 0x02 -> handleLoginSuccess(raw.payload());
      case 0x00 -> handleLoginDisconnect(ctx, raw.payload());
      case 0x04 -> handleLoginPluginRequest(ctx, raw.payload());
    }
  }

  public void sendHandshake(String host, int port) {
    Thread.ofVirtual().name("java-handshake").start(() -> {
      String loginName = resolveLoginName();
      ForwardingMode mode = server.getConfig().getForwarding().getMode();

      String effectiveHost = host;
      if (mode == ForwardingMode.BUNGEECORD || mode == ForwardingMode.BUNGEEGUARD) {
        effectiveHost = buildBungeeHostname(host, mode);
      }

      ByteBuf hs = session.getJavaChannel().alloc().buffer();
      VarIntUtil.write(hs, JavaSession.JAVA_PROTOCOL);
      StringUtil.writeJavaString(hs, effectiveHost);
      hs.writeShort(port);
      VarIntUtil.write(hs, 2);
      session.getJavaChannel().write(new RawPacket(0x00, hs));

      ByteBuf ls = session.getJavaChannel().alloc().buffer();
      StringUtil.writeJavaString(ls, loginName);
      session.getJavaChannel().writeAndFlush(new RawPacket(0x00, ls));
    });
  }

  /**
   * MinecraftConsoles fork: true when this client brought its own Java account and therefore
   * expects the auth handshake (packets 170/171/172) rather than the stock LCE login.
   */
  private boolean isAuthRelayClient() {
    return cs.getClientVersion() >= 80 && cs.getLceClientMojangUuid() != null
        && !cs.getLceClientMojangUuid().isEmpty();
  }

  /**
   * MinecraftConsoles fork: re-opens reading on the console socket before we ask the client a
   * question.
   *
   * <p>{@code initiateJavaConnection()} sets autoRead(false) and only {@code onJoinGame()} sets it
   * back, which is right for gameplay - it stops the console flooding us before a world exists.
   * It is fatal for the auth exchange: onJoinGame cannot happen until the login completes, the
   * login cannot complete without the client's answer, and with reading disabled that answer sits
   * unread in the kernel buffer forever. The scheme goes out, the client replies, and nothing
   * happens - no error anywhere, because nobody is listening.
   *
   * <p>Reading early is safe: at this point the client sends its auth response and then its login
   * packet, and both are handled without needing a world.
   */
  private void openConsoleForReply() {
    Channel ch = cs.getConsoleChannel();
    if (ch != null && ch.isActive()) {
      ch.config().setAutoRead(true);
    }
  }

  private void handleLoginSuccess(ByteBuf buf) {
    // ---- MinecraftConsoles fork ---------------------------------------------------------------
    // Reaching LoginSuccess without having sent a scheme means the Java server is in offline mode:
    // it never asked us to encrypt, so handleEncryptionRequest never ran. A protocol-80 client is
    // sitting on its pre-login waiting for a scheme it would now wait for forever, because there
    // is no longer anything left in the login sequence to produce one.
    //
    // Offer it "offline". The client answers with the scheme it can satisfy and finishes its own
    // handshake, which is what makes an offline-mode server behave like any other rather than like
    // a server that half-connects.
    if (isAuthRelayClient() && !schemeSent) {
      schemeSent = true;
      log.info("[Auth-Relay] {} is offline mode - offering the 'offline' scheme to '{}'",
          cs.getCurrentServer() == null ? "the backend" : cs.getCurrentServer(),
          cs.getPlayerName());
      openConsoleForReply();
      PacketManager.sendToConsole(cs,
          new ConsoleAuthSchemeS2CPacket(java.util.List.of("offline"), ""));
    }

    try {
      String uuidStr = StringUtil.readJavaString(buf.duplicate());
      String javaName = StringUtil.readJavaString(buf.duplicate());
      UUID uuid = UUID.fromString(uuidStr);
      cs.setUuid(uuid);
      log.info("[JavaSession] Login from {} uuid={}",
          cs.getPlayerName(), uuid);
    } catch (Exception e) {
      log.warn("[JavaSession] Failed to parse Login from UUID for {}: {}",
          cs.getPlayerName(), e.getMessage());
    }
  }

  private void handleLoginDisconnect(ChannelHandlerContext ctx, ByteBuf buf) {
    try {
      String reasonJson = StringUtil.readJavaString(buf);
      // MinecraftConsoles fork: read the WHOLE component, not just its top-level text.
      //
      // A modern kick message usually has an empty "text" and puts everything the player is meant
      // to read in "extra". Taking only "text" therefore turned a real explanation into an empty
      // string, and the log said 'Reason:' followed by nothing - the one thing that would have
      // explained the kick, thrown away at the last step. Seen against Hypixel.
      String parsedReason = flattenComponent(reasonJson);
      if (parsedReason.isEmpty()) {
        // Nothing readable came out. The raw JSON is ugly but it is what the server said, and an
        // ugly answer beats no answer.
        parsedReason = reasonJson;
      }
      session.setDisconnectReason(parsedReason);
      log.warn("[Disconnect] Java Server rejected login for {}. Reason: {}",
          cs.getPlayerName(), parsedReason);
      log.warn("[Disconnect] raw reason: {}", reasonJson);
    } catch (Exception e) {
      log.warn("[Disconnect] Java Server rejected login for {} (0x00, unreadable reason).",
          cs.getPlayerName());
    }
    ctx.close();
  }

  /**
   * MinecraftConsoles fork: reduces a chat component to the text a person would read.
   *
   * <p>A component is a bare string, or an object with "text", or an object whose real content
   * hangs off "extra" as a list of more of the same - and servers still send all three, often
   * nested. Section-sign colour codes are stripped: the destination is a log line.
   *
   * <p>Returns "" when nothing readable can be extracted, which the caller treats as a signal to
   * fall back to the raw JSON rather than report an empty reason.
   */
  private static String flattenComponent(String json) {
    StringBuilder sb = new StringBuilder();
    try {
      appendComponent(com.alibaba.fastjson2.JSON.parse(json), sb, 0);
    } catch (Exception e) {
      return "";
    }
    return sb.toString().replaceAll("§.", "").replace('\n', ' ').trim();
  }

  private static void appendComponent(Object node, StringBuilder sb, int depth) {
    // Components nest, and nothing stops a server sending a pathological one.
    if (node == null || depth > 16) {
      return;
    }
    if (node instanceof String s) {
      sb.append(s);
      return;
    }
    if (node instanceof com.alibaba.fastjson2.JSONObject obj) {
      String text = obj.getString("text");
      if (text != null) {
        sb.append(text);
      }
      String translate = obj.getString("translate");
      if (translate != null && (text == null || text.isEmpty())) {
        sb.append(translate);
      }
      appendComponent(obj.get("extra"), sb, depth + 1);
      return;
    }
    if (node instanceof com.alibaba.fastjson2.JSONArray arr) {
      for (Object child : arr) {
        appendComponent(child, sb, depth + 1);
      }
    }
  }

  private void handleLoginPluginRequest(ChannelHandlerContext ctx, ByteBuf buf) {
    int messageId = VarIntUtil.read(buf);
    String channel = StringUtil.readJavaString(buf);

    if (!VELOCITY_CHANNEL.equals(channel)) {
      sendPluginFailure(ctx, messageId);
      return;
    }

    if (!server.getConfig().getForwarding().isVelocity()) {
      log.warn("[Velocity] Backend requested modern forwarding but mode is '{}'. "
              + "Set forwarding.mode=VELOCITY to enable it.",
          server.getConfig().getForwarding().getMode());
      sendPluginFailure(ctx, messageId);
      return;
    }

    String secret = server.getConfig().getForwarding().getVelocitySecret();
    if (secret == null || secret.isEmpty()) {
      log.warn("[Velocity] Backend requested modern forwarding but velocity-secret is not set.");
      sendPluginFailure(ctx, messageId);
      return;
    }

    int requestedVersion = buf.isReadable() ? buf.readByte() : 1;
    int responseVersion = Math.min(requestedVersion, 1);

    try {
      String playerIp = ((InetSocketAddress) cs.getConsoleChannel().remoteAddress())
          .getAddress().getHostAddress();
      UUID uuid = resolveUuid();
      String username = resolveLoginName();

      ByteBuf data = Unpooled.buffer();
      VarIntUtil.write(data, responseVersion);
      writeString(data, playerIp);
      data.writeLong(uuid.getMostSignificantBits());
      data.writeLong(uuid.getLeastSignificantBits());
      writeString(data, username);
      VarIntUtil.write(data, 0);

      byte[] dataBytes = new byte[data.readableBytes()];
      data.readBytes(dataBytes);
      data.release();

      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] sig = mac.doFinal(dataBytes);

      ByteBuf resp = ctx.alloc().buffer();
      VarIntUtil.write(resp, messageId);
      resp.writeBoolean(true);
      resp.writeBytes(sig);
      resp.writeBytes(dataBytes);

      ctx.writeAndFlush(new RawPacket(0x02, resp));
      log.debug("[Velocity] Forwarding response sent for {} (version {})", username,
          responseVersion);
    } catch (Exception e) {
      log.error("[Velocity] Failed to build forwarding response: {}", e.getMessage(), e);
      sendPluginFailure(ctx, messageId);
    }
  }

  private void handleEncryptionRequest(ChannelHandlerContext ctx, ByteBuf buf) {
    String serverId = StringUtil.readJavaString(buf);
    byte[] pubKeyBytes = new byte[VarIntUtil.read(buf)];
    buf.readBytes(pubKeyBytes);
    byte[] verifyToken = new byte[VarIntUtil.read(buf)];
    buf.readBytes(verifyToken);

    Thread.ofVirtual().name("mc-auth-join").start(() -> {
      try {
        KeyGenerator gen = KeyGenerator.getInstance("AES");
        gen.init(128);
        SecretKey secretKey = gen.generateKey();

        PublicKey publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(new X509EncodedKeySpec(pubKeyBytes));

        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(serverId.getBytes(StandardCharsets.ISO_8859_1));
        digest.update(secretKey.getEncoded());
        digest.update(pubKeyBytes);
        String hash = new BigInteger(digest.digest()).toString(16);

        // ---- MinecraftConsoles fork: relay the join to the LCE client -------------------------
        // A protocol-80 client carries its own Mojang account (its auth manager already knows how
        // to call sessionserver/join). In that case the proxy must NOT authenticate as itself:
        // it hands the client the hash it just computed and lets the player's own access token
        // prove ownership. The shared secret never leaves this process and the access token never
        // leaves the client - the hash is the only thing that crosses, and it is useless on its
        // own.
        //
        // The Java handshake is suspended here and resumed in onConsoleAuthResponse().
        //
        // This check MUST come before anything that touches the proxy's own account, and it did
        // not: AuthUtil.getSession() used to be called a few lines above, to fetch a token only
        // the non-relay branch ever uses. When the proxy has no account of its own that call opens
        // an interactive Microsoft device-code login and blocks on it forever, so the relay below
        // was never reached and every join to an online-mode server ended in a timeout - with a
        // sign-in code printed to a console log nobody was reading. Nothing in the relay path
        // needs the proxy's identity: the hash above is computed from the server's own values.
        if (isAuthRelayClient()) {
          this.pending = new PendingEncryption(ctx, secretKey, publicKey, verifyToken);
          this.schemeSent = true;
          log.info("[Auth-Relay] asking LCE client '{}' to authenticate itself (uuid={})",
              cs.getPlayerName(), cs.getLceClientMojangUuid());
          openConsoleForReply();
          PacketManager.sendToConsole(cs,
              new ConsoleAuthSchemeS2CPacket(java.util.List.of("mojang"), hash));
          return;
        }

        // Only from here on is the proxy authenticating as ITSELF, which is the only case that
        // has any business asking for its credentials.
        JavaAuthManager auth = AuthUtil.getSession();
        String accessToken = auth.getMinecraftToken().getUpToDate().getToken();
        String uuid = auth.getMinecraftProfile().getUpToDate().getId()
            .toString().replace("-", "");

        com.alibaba.fastjson2.JSONObject payload = new com.alibaba.fastjson2.JSONObject();
        payload.put("accessToken", accessToken);
        payload.put("selectedProfile", uuid);
        payload.put("serverId", hash);

        HttpURLConnection conn = (HttpURLConnection) new URL(
            "https://sessionserver.mojang.com/session/minecraft/join").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.getOutputStream().write(payload.toString().getBytes(StandardCharsets.UTF_8));

        if (conn.getResponseCode() != 204) {
          log.error("Failed to verify session with Mojang (Code: {})", conn.getResponseCode());
          ctx.close();
          return;
        }

        completeEncryption(ctx, secretKey, publicKey, verifyToken);
      } catch (Exception e) {
        log.error("Authentication error for {}: {}", cs.getPlayerName(), e.getMessage(), e);
        ctx.close();
      }
    });
  }

  // ---- MinecraftConsoles fork: protocol-80 auth relay -----------------------------------------

  /** Java-side encryption state parked while the LCE client authenticates itself. */
  private record PendingEncryption(ChannelHandlerContext ctx, SecretKey secretKey,
                                   PublicKey publicKey, byte[] verifyToken) {

  }

  private volatile PendingEncryption pending;

  /**
   * Whether an auth scheme has already gone to the client on this connection. Exactly one is ever
   * sent, and which one depends on how the Java server behaved: "mojang" with a real hash if it
   * asked us to encrypt, "offline" if it went straight to LoginSuccess.
   */
  private volatile boolean schemeSent;

  /**
   * Resumes the suspended Java encryption handshake once the LCE client has posted to
   * sessionserver/join with its own token. Called from ConsoleAuthResponseC2SPacket.
   *
   * <p>The proxy does NOT re-verify with hasJoined: that is the Java server's job, and it is about
   * to do exactly that with the username we sent in LoginStart. All that is left here is to prove
   * to the server that we hold the shared secret.
   */
  public void onConsoleAuthResponse(String chosenScheme, String username) {
    PendingEncryption p = this.pending;
    this.pending = null;

    if (p == null) {
      // No handshake parked means we offered "offline" from handleLoginSuccess: the Java server
      // is in offline mode and there is nothing to resume. The client is nonetheless waiting for
      // a result before it considers itself logged in, so answer it - dropping the exchange here
      // would leave it in the game but with no identity of its own.
      if ("offline".equals(chosenScheme)) {
        log.info("[Auth-Relay] '{}' accepted the offline scheme", cs.getPlayerName());
        PacketManager.sendToConsole(cs, new ConsoleAuthResultS2CPacket(
            true,
            cs.getLceClientMojangUuid() == null ? "" : cs.getLceClientMojangUuid(),
            username == null || username.isEmpty() ? cs.getPlayerName() : username,
            ""));
        return;
      }
      log.warn("[Auth-Relay] auth response from '{}' with no handshake in flight - ignored",
          cs.getPlayerName());
      return;
    }

    if (!"mojang".equals(chosenScheme)) {
      // The client could not (or would not) authenticate online. There is nothing to fall back
      // to: an online-mode Java server will reject us at hasJoined anyway, and continuing would
      // just turn a clear error into a confusing disconnect several packets later.
      log.error("[Auth-Relay] client '{}' answered scheme '{}' - cannot join an online-mode server",
          cs.getPlayerName(), chosenScheme);
      PacketManager.sendToConsole(cs, new ConsoleAuthResultS2CPacket(
          false, "", "", "This server requires a Mojang account"));
      p.ctx().close();
      return;
    }

    log.info("[Auth-Relay] client '{}' authenticated as '{}' - resuming Java handshake",
        cs.getPlayerName(), username);
    try {
      completeEncryption(p.ctx(), p.secretKey(), p.publicKey(), p.verifyToken());
      PacketManager.sendToConsole(cs, new ConsoleAuthResultS2CPacket(
          true, cs.getLceClientMojangUuid(), username, ""));
    } catch (Exception e) {
      log.error("[Auth-Relay] failed to resume handshake for {}: {}",
          cs.getPlayerName(), e.getMessage(), e);
      p.ctx().close();
    }
  }

  /**
   * The half of the handshake that is identical whoever did the sessionserver/join: RSA-encrypt
   * the shared secret and the verify token, send EncryptionResponse, then install the AES/CFB8
   * pipeline once the bytes are on the wire.
   */
  private void completeEncryption(ChannelHandlerContext ctx, SecretKey secretKey,
      PublicKey publicKey, byte[] verifyToken) throws Exception {
    Cipher rsa = Cipher.getInstance("RSA");
    rsa.init(Cipher.ENCRYPT_MODE, publicKey);
    byte[] encSecret = rsa.doFinal(secretKey.getEncoded());
    byte[] encToken = rsa.doFinal(verifyToken);

    ByteBuf resp = ctx.alloc().buffer();
    VarIntUtil.write(resp, encSecret.length);
    resp.writeBytes(encSecret);
    VarIntUtil.write(resp, encToken.length);
    resp.writeBytes(encToken);

    ctx.writeAndFlush(new RawPacket(0x01, resp)).addListener(f -> {
      try {
        Cipher decrypt = Cipher.getInstance("AES/CFB8/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, secretKey,
            new IvParameterSpec(secretKey.getEncoded()));
        Cipher encrypt = Cipher.getInstance("AES/CFB8/NoPadding");
        encrypt.init(Cipher.ENCRYPT_MODE, secretKey,
            new IvParameterSpec(secretKey.getEncoded()));

        ctx.pipeline().addFirst("decrypt", new JavaCipherDecoder(decrypt));
        ctx.pipeline().addFirst("encrypt", new JavaCipherEncoder(encrypt));
        log.info("Encryption enabled for Java connection of {}.", cs.getPlayerName());
      } catch (Exception ex) {
        log.error("Failed to install cipher pipeline: {}", ex.getMessage());
        ctx.close();
      }
    });
  }

  private String buildBungeeHostname(String realHost, ForwardingMode mode) {
    try {
      String playerIp = ((InetSocketAddress) cs.getConsoleChannel().remoteAddress())
          .getAddress().getHostAddress();
      UUID uuid = resolveUuid();

      JSONArray props = new JSONArray();
      if (mode == ForwardingMode.BUNGEEGUARD) {
        String token = server.getConfig().getForwarding().getBungeeGuardToken();
        if (token != null && !token.isEmpty()) {
          JSONObject tokenProp = new JSONObject();
          tokenProp.put("name", "bungeeguard-token");
          tokenProp.put("value", token);
          props.add(tokenProp);
        }
      }
      return realHost + "\00" + playerIp + "\00" + uuid + "\00" + props.toJSONString();
    } catch (Exception e) {
      log.error("[{}] Failed to build forwarding hostname: {}", mode.name(), e.getMessage(), e);
      return realHost;
    }
  }

  private void sendPluginFailure(ChannelHandlerContext ctx, int messageId) {
    ByteBuf resp = ctx.alloc().buffer();
    VarIntUtil.write(resp, messageId);
    resp.writeBoolean(false);
    ctx.writeAndFlush(new RawPacket(0x02, resp));
  }

  private String resolveLoginName() {
    // MinecraftConsoles fork: when the LCE client brings its own Mojang account, LoginStart must
    // carry THAT player's name. The Java server looks up exactly this name at hasJoined, against
    // the join the client itself performed - send the proxy's profile name here and the server
    // would check an account nobody authenticated. cs.getPlayerName() is the name the client's
    // auth manager chose, and is deliberately not given the configured player prefix.
    //
    // lceAuthName is preferred over playerName because they are not always the same string: the
    // gamertag shown on the LCE side can differ from the name on the Mojang account, and it is the
    // account name the server looks up at hasJoined. Both arrive with the PRE-login, which is what
    // makes them available here at all - see ConsoleSession.lceAuthName.
    if (cs.getClientVersion() >= 80 && cs.getLceClientMojangUuid() != null
        && !cs.getLceClientMojangUuid().isEmpty()) {
      String authName = cs.getLceAuthName();
      return authName != null && !authName.isEmpty() ? authName : cs.getPlayerName();
    }
    if (server.getConfig().getAuth().isOnlineMode() && AuthUtil.hasSession()) {
      try {
        return AuthUtil.getSession().getMinecraftProfile().getUpToDate().getName();
      } catch (Exception e) {
        log.error("Failed to retrieve profile name: {}", e.getMessage());
      }
    }
    return server.getConfig().getConnection().getPlayerPrefix() + cs.getPlayerName();
  }

  private UUID resolveUuid() {
    UUID uuid = cs.getUuid();
    if (uuid != null) {
      return uuid;
    }
    return UUID.nameUUIDFromBytes(
        ("OfflinePlayer:" + cs.getPlayerName()).getBytes(StandardCharsets.UTF_8));
  }
}