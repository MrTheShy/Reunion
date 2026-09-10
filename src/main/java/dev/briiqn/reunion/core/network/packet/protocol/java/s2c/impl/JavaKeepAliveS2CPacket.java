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

package dev.briiqn.reunion.core.network.packet.protocol.java.s2c.impl;

import dev.briiqn.reunion.core.network.packet.annotation.PacketInfo;
import dev.briiqn.reunion.core.network.packet.data.PacketSide;
import dev.briiqn.reunion.core.network.packet.manager.PacketManager;
import dev.briiqn.reunion.core.network.packet.protocol.console.s2c.impl.ConsoleKeepAliveS2CPacket;
import dev.briiqn.reunion.core.network.packet.protocol.java.s2c.JavaS2CPacket;
import dev.briiqn.reunion.core.session.JavaSession;
import dev.briiqn.reunion.core.util.VarIntUtil;
import io.netty.buffer.ByteBuf;

@lombok.extern.log4j.Log4j2
@PacketInfo(side = PacketSide.JAVA_S2C, id = 0x00, supports = {47})
public final class JavaKeepAliveS2CPacket extends JavaS2CPacket {

  /** How many keep-alives the server has asked for. See the note in handle(). */
  private static int asked = 0;

  private int keepAliveId;

  public JavaKeepAliveS2CPacket() {
  }

  @Override
  public void read(ByteBuf buf) {
    keepAliveId = VarIntUtil.read(buf);
  }

  @Override
  public void handle(JavaSession session) {
    session.getConsoleSession().onKeepAliveSent();
    // MinecraftConsoles fork: the other half of the round trip. Pair this with the line
    // ConsoleKeepAliveC2SPacket prints - a request with no answer after it is a client that has
    // gone quiet, and a Java server drops such a client at thirty seconds.
    log.info("[KEEPALIVE] server asked id={} (request #{}) -> forwarding to the console",
        keepAliveId, ++asked);
    PacketManager.sendToConsole(session.getConsoleSession(),
        new ConsoleKeepAliveS2CPacket(keepAliveId));
  }
}