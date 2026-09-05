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
import java.util.List;

/**
 * MinecraftConsoles fork - protocol 80 only.
 *
 * <p>Asks the LCE client to authenticate ITSELF against the Mojang session server, instead of the
 * proxy doing it with an account of its own. {@code serverId} is not a random challenge here: it is
 * the SHA-1 digest the Java server's encryption handshake requires
 * (serverId + shared secret + server public key), computed in
 * {@code JavaLoginHandler.handleEncryptionRequest}. The client posts exactly that string to
 * sessionserver/join with its own access token, so the shared secret never leaves the proxy and
 * the access token never leaves the client.
 *
 * <p>Mirrors AuthSchemePacket (id 170) in Minecraft.World: int count, count x UTF scheme, UTF
 * serverId. The client only accepts the scheme names "mojang", "offline" and "elyby".
 */
@PacketInfo(side = PacketSide.CONSOLE_S2C, id = 170, supports = {80})
public final class ConsoleAuthSchemeS2CPacket extends ConsoleS2CPacket {

  private final List<String> schemes;
  private final String serverId;

  public ConsoleAuthSchemeS2CPacket() {
    this(List.of("offline"), "");
  }

  public ConsoleAuthSchemeS2CPacket(List<String> schemes, String serverId) {
    this.schemes = schemes;
    this.serverId = serverId == null ? "" : serverId;
  }

  @Override
  public void write(ByteBuf buf) {
    buf.writeInt(schemes.size());
    for (String s : schemes) {
      writeConsoleUtf(buf, s, 32);
    }
    writeConsoleUtf(buf, serverId, 64);
  }
}
