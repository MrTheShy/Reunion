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

import static dev.briiqn.reunion.core.util.StringUtil.readConsoleUtf;

import dev.briiqn.reunion.api.event.player.PreLoginEvent;
import dev.briiqn.reunion.core.network.packet.annotation.PacketInfo;
import dev.briiqn.reunion.core.network.packet.data.PacketSide;
import dev.briiqn.reunion.core.network.packet.manager.PacketManager;
import dev.briiqn.reunion.core.network.packet.protocol.console.c2s.ConsoleC2SPacket;
import dev.briiqn.reunion.core.network.packet.protocol.console.s2c.impl.ConsoleDisconnectS2CPacket;
import dev.briiqn.reunion.core.network.packet.protocol.console.s2c.impl.ConsolePreLoginS2CPacket;
import dev.briiqn.reunion.core.plugin.hooks.PluginEventHooks;
import dev.briiqn.reunion.core.session.ConsoleSession;
import io.netty.buffer.ByteBuf;
import java.net.InetSocketAddress;
import lombok.extern.log4j.Log4j2;

@Log4j2
@PacketInfo(side = PacketSide.CONSOLE_C2S, id = 2, supports = {39, 78, 80})
public final class ConsolePreLoginC2SPacket extends ConsoleC2SPacket {

  private int clientVersion;
  private String playerName;
  // MinecraftConsoles fork, protocol 80: the Java identity the client's own auth manager resolved.
  // Empty on 39/78, which have no such fields.
  private String mojangUuid = "";
  private String authName = "";

  public ConsolePreLoginC2SPacket() {
  }

  @Override
  public void read(ByteBuf buf) {
    clientVersion = buf.readUnsignedShort();
    playerName = readConsoleUtf(buf);

    buf.readByte(); // friendsOnlyBits
    buf.readInt();  // ugcPlayersVersion

    int playerCount = buf.readUnsignedByte();
    for (int i = 0; i < playerCount; i++) {
      // A PlayerUID is 8 bytes up to protocol 78 and 16 from 80 on - the same widening that made
      // LoginPacket's two XUIDs into one 128-bit id. Reading the wrong width here desyncs the rest
      // of the packet, and it goes unnoticed because a single player joining sends zero ids.
      buf.readLong();
      if (clientVersion >= 80) {
        buf.readLong();
      }
    }

    buf.skipBytes(14); // szUniqueSaveName
    buf.readInt();     // serverSettings
    buf.readByte();    // hostIndex
    buf.readInt();     // texturePackId

    // ---- MinecraftConsoles fork: protocol 80 ------------------------------------------------
    // The client's Java identity, carried on the FIRST packet rather than the login. See the note
    // on ConsoleSession.lceAuthName for why the login would be too late: the Java connection is
    // opened from this very handler.
    //
    // Consuming these is not optional. ConsoleConnectionHandler loops while the frame is readable,
    // so bytes left behind would be parsed as the next packet's id and take the connection down.
    if (clientVersion >= 80) {
      mojangUuid = readConsoleUtf(buf);
      authName = readConsoleUtf(buf);
    }
  }

  @Override
  public void write(ByteBuf buf) {
  }

  @Override
  public void handle(ConsoleSession session) {
    session.setClientVersion(clientVersion);
    session.setPlayerName(playerName);

    // ---- MinecraftConsoles fork: protocol 80 ------------------------------------------------
    // Must land before initiateJavaConnection() below, which is the whole reason these travel on
    // the pre-login. Both are needed together and mean different things: the uuid decides WHETHER
    // to relay the encryption handshake to the client, the name decides what we put in LoginStart
    // and therefore which account the Java server checks at hasJoined.
    if (clientVersion >= 80) {
      if (mojangUuid != null && !mojangUuid.isEmpty()) {
        session.setLceClientMojangUuid(mojangUuid);
      }
      if (authName != null && !authName.isEmpty()) {
        session.setLceAuthName(authName);
      }
      log.info("[Console] pre-login from protocol {} client '{}' (java identity: {} / {})",
          clientVersion, playerName,
          authName == null || authName.isEmpty() ? "<none>" : authName,
          mojangUuid == null || mojangUuid.isEmpty() ? "<none>" : mojangUuid);
    }

    if (session.getServer().getSessions().size() >= session.getServer().getConfig().getGameplay().getMaxPlayers()) {
      PacketManager.sendToConsole(session, new ConsoleDisconnectS2CPacket());
      session.getConsoleChannel().close();
      return;
    }

    InetSocketAddress address =
        (InetSocketAddress) session.getConsoleChannel().remoteAddress();

    PreLoginEvent event = PluginEventHooks.firePreLogin(
        session, playerName, address, clientVersion);

    switch (event.result().status()) {

      case DENIED -> {
        PacketManager.sendToConsole(session, new ConsoleDisconnectS2CPacket());
        session.getConsoleChannel().close();
      }

      case HELD -> {
        PacketManager.sendToConsole(session,
            new ConsolePreLoginS2CPacket(clientVersion, playerName));
        session.getConsoleChannel().config().setAutoRead(true);
      }

      default -> session.initiateJavaConnection();
    }
  }
}