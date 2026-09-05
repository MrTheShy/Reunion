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

package dev.briiqn.reunion.core.network.packet.protocol.console.c2s.impl;

import dev.briiqn.reunion.api.event.player.LoginEvent;
import dev.briiqn.reunion.core.ReunionServer;
import dev.briiqn.reunion.core.network.packet.annotation.PacketInfo;
import dev.briiqn.reunion.core.network.packet.data.PacketSide;
import dev.briiqn.reunion.core.network.packet.data.enums.DisconnectReason;
import dev.briiqn.reunion.core.network.packet.manager.PacketManager;
import dev.briiqn.reunion.core.network.packet.protocol.console.c2s.ConsoleC2SPacket;
import dev.briiqn.reunion.core.network.packet.protocol.console.s2c.impl.ConsoleDisconnectS2CPacket;
import dev.briiqn.reunion.core.network.packet.protocol.console.s2c.impl.ConsoleLoginS2CPacket;
import dev.briiqn.reunion.core.network.packet.protocol.console.s2c.impl.ConsoleTextureAndGeometryS2CPacket;
import dev.briiqn.reunion.core.plugin.hooks.PluginEventHooks;
import dev.briiqn.reunion.core.plugin.session.ConsoleSessionPlayerAdapter;
import dev.briiqn.reunion.core.session.ConsoleSession;
import dev.briiqn.reunion.core.util.StringUtil;
import dev.briiqn.reunion.core.util.game.SkinUtil;
import io.netty.buffer.ByteBuf;
import lombok.extern.log4j.Log4j2;

@Log4j2
@PacketInfo(side = PacketSide.CONSOLE_C2S, id = 1, supports = {39, 78, 80})
public final class ConsoleLoginC2SPacket extends ConsoleC2SPacket {

  private int clientVersion;
  private String username;
  private int skinId;
  private int capeId;
  private long offlineXuid;
  private long onlineXuid;
  // MinecraftConsoles fork, protocol 80: the dashed Mojang UUID the client's own auth manager
  // resolved (MCAuth). Empty on 39/78, which have no such field.
  private String mojangUuid = "";

  public ConsoleLoginC2SPacket() {
  }

  @Override
  public void read(ByteBuf buf) {
    clientVersion = buf.readInt();
    username = StringUtil.readConsoleUtf(buf);
    StringUtil.readConsoleUtf(buf);      // levelType
    buf.readLong();                      // seed
    buf.readInt();                       // gameType
    buf.readByte();                      // dimension
    buf.readByte();                      // mapHeight
    buf.readByte();                      // maxPlayers
    offlineXuid = buf.readLong();        // offlineXuid
    onlineXuid = buf.readLong();         // onlineXuid
    buf.readBoolean();                   // friendsOnlyUGC
    buf.readInt();                       // ugcPlayersVersion
    buf.readByte();                      // difficulty
    buf.readInt();                       // multiplayerInstanceId
    buf.readByte();                      // playerIndex
    skinId = buf.readInt();              // m_playerSkinId
    capeId = buf.readInt();              // m_playerCapeId
    buf.readBoolean();                   // isGuest
    buf.readBoolean();                   // newSeaLevel
    buf.readInt();                       // uiGamePrivileges
    buf.readShort();                     // xzSize
    buf.readByte();                      // hellScale

    // ---- MinecraftConsoles fork: protocol 80 ------------------------------------------------
    // Handled here rather than in a read80() override, even though PacketRegistry supports
    // per-protocol methods: the dispatcher picks the override from session.getClientVersion(),
    // and for THIS packet the session has no version yet - it is set from this very packet's
    // handle(). clientVersion is the first field on the wire, so the read is self-describing.
    //
    // Two things differ at 80, and only the second one is visible here:
    //  * the two 8-byte ids above are no longer offline/online XUIDs; they are the high and low
    //    halves of ONE 128-bit GameUUID. Same 16 bytes, so nothing to change in the read - see
    //    handle() for where the meaning matters.
    //  * a dashed Mojang UUID string is appended. It MUST be consumed: ConsoleConnectionHandler
    //    loops `while (data.isReadable())` over the frame, so leftover bytes are not discarded -
    //    they would be parsed as the next packet's id and desync the whole connection.
    if (clientVersion >= 80) {
      mojangUuid = StringUtil.readConsoleUtf(buf);
    }
  }

  @Override
  public void write(ByteBuf buf) {
  }

  @Override
  public void handle(ConsoleSession session) {
    if (session.getServer().getBanManager().isBanned(username)) {
      log.info("{} tried to join but is banned for '{}'", username,
          session.getServer().getBanManager().getBanReason(username));
      PacketManager.sendToConsole(session, new ConsoleDisconnectS2CPacket(DisconnectReason.BANNED));
      session.getConsoleChannel().close();
      return;
    }

    log.info("[Console] Login: version={} username={}", clientVersion, username);
    log.debug("[LCE Skin] {} raw skinId=0x{} capeId=0x{}",
        username,
        Integer.toUnsignedString(skinId, 16).toUpperCase(),
        Integer.toUnsignedString(capeId, 16).toUpperCase());

    session.setClientVersion(clientVersion);
    session.setPlayerName(username);
    session.setXuid(onlineXuid != 0 ? onlineXuid : offlineXuid);

    // ---- MinecraftConsoles fork: protocol 80 ------------------------------------------------
    // At 78 the two 8-byte slots are separate offline/online XUIDs and the line above picks one.
    // At 80 they are the high and low halves of a single 128-bit id, so picking "one" would hand
    // us half a UUID. Reassemble it, and keep the dashed string the client's auth manager sent -
    // that string is what the Mojang session server is asked about during the encryption
    // handshake, so the account being authenticated is the PLAYER'S, not the proxy's.
    if (clientVersion >= 80) {
      session.setLceClientUuid(new java.util.UUID(offlineXuid, onlineXuid));
      if (mojangUuid != null && !mojangUuid.isEmpty()) {
        session.setLceClientMojangUuid(mojangUuid);
      }
      log.info("[Console] protocol 80 client: uuid={} mojangUuid='{}'",
          session.getLceClientUuid(), mojangUuid);
    }

    if (clientVersion > ReunionServer.maxSupportedClientProtocol) {
      log.warn("[Console] Client version {} might not be fully supported (expected 78)",
          clientVersion);
    }

    String skinName = SkinUtil.getSkinPathFromId(skinId);
    String capeName = SkinUtil.getCapePathFromId(capeId);

    if (skinName != null) {
      session.setLceSkinName(skinName);
      session.setLceSkinDwId(skinId);
      session.getServer().registerTextureOwner(skinName, session);
      log.debug("[LCE Skin] {} skin='{}'", username,
          skinName);

      PacketManager.sendToConsole(session,
          new ConsoleTextureAndGeometryS2CPacket(skinName, skinId));
    }

    if (capeName != null) {
      session.setLceCapeName(capeName);
      session.getServer().registerTextureOwner(capeName, session);
      log.debug("[LCE Skin] {} cape='{}'", username,
          capeName);
      PacketManager.sendToConsole(session,
          new ConsoleTextureAndGeometryS2CPacket(capeName, capeId));
    } else {
      log.debug("[LCE Skin] {} capeId=0x{}", username,
          Integer.toUnsignedString(capeId, 16).toUpperCase());
    }

    session.getServer().addSession(username, session);

    var apiPlayer = new ConsoleSessionPlayerAdapter(session);

    if (PluginEventHooks.isHeld(session)) {
      session.initChunkManager();

      int tempSafeId = session.getEntityManager().allocConsoleId();
      session.setSafePlayerId(tempSafeId);

      PacketManager.sendToConsole(session, new ConsoleLoginS2CPacket(
          tempSafeId,
          username.substring(0, Math.min(username.length(), 16)),
          "default", 0L, 0, 0, 2, session.getSmallId(), session.getPlayerPrivileges()
      ));

      session.setLoggedIn(true);
      session.getConsoleChannel().config().setAutoRead(true);
      PluginEventHooks.fireHeld(apiPlayer);
      return;
    }

    LoginEvent event = PluginEventHooks.fireLogin(apiPlayer);
    if (!event.isAllowed()) {
      String reason = event.result().reason().orElse("Denied.");
      log.debug("Login denied for {} by plugin: {}", username, reason);
      PacketManager.sendToConsole(session, new ConsoleDisconnectS2CPacket());
      session.getConsoleChannel().close();
      session.getServer().removeSession(username);
    }
  }
}