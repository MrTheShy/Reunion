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

package dev.briiqn.reunion.core.network.packet.protocol.console.s2c.impl;

import static dev.briiqn.reunion.core.util.StringUtil.writeConsoleUtf;

import dev.briiqn.reunion.core.network.packet.annotation.PacketInfo;
import dev.briiqn.reunion.core.network.packet.data.PacketSide;
import dev.briiqn.reunion.core.network.packet.protocol.console.s2c.ConsoleS2CPacket;
import io.netty.buffer.ByteBuf;

/**
 * MinecraftConsoles fork - protocol 80 only. Result of the auth exchange started by
 * {@link ConsoleAuthSchemeS2CPacket}.
 *
 * <p>Mirrors AuthResultPacket (id 172) in Minecraft.World: bool success, UTF assignedUuid,
 * UTF assignedUsername, UTF errorMessage, UTF skinKey, int skinLength + raw skin bytes.
 *
 * <p>The proxy never sends skin bytes - that path exists for the fork's own server, which serves
 * Mojang skins inline. Here the skin already arrives over the normal LCE texture packets, so the
 * length is always written as 0. It still has to be written: the client reads the field
 * unconditionally, and ConsoleConnectionHandler loops over the frame, so a short packet would
 * desync the connection rather than merely lose a skin.
 */
@PacketInfo(side = PacketSide.CONSOLE_S2C, id = 172, supports = {80})
public final class ConsoleAuthResultS2CPacket extends ConsoleS2CPacket {

  private final boolean success;
  private final String assignedUuid;
  private final String assignedUsername;
  private final String errorMessage;

  public ConsoleAuthResultS2CPacket() {
    this(false, "", "", "");
  }

  public ConsoleAuthResultS2CPacket(boolean success, String assignedUuid, String assignedUsername,
      String errorMessage) {
    this.success = success;
    this.assignedUuid = assignedUuid == null ? "" : assignedUuid;
    this.assignedUsername = assignedUsername == null ? "" : assignedUsername;
    this.errorMessage = errorMessage == null ? "" : errorMessage;
  }

  @Override
  public void write(ByteBuf buf) {
    buf.writeBoolean(success);
    writeConsoleUtf(buf, assignedUuid, 64);
    writeConsoleUtf(buf, assignedUsername, 64);
    writeConsoleUtf(buf, errorMessage, 256);
    writeConsoleUtf(buf, "", 256); // skinKey - unused by the proxy
    buf.writeInt(0);               // inline skin length - see the class comment
  }
}
