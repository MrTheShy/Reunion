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

import dev.briiqn.reunion.core.network.packet.annotation.PacketInfo;
import dev.briiqn.reunion.core.network.packet.data.PacketSide;
import dev.briiqn.reunion.core.network.packet.manager.PacketManager;
import dev.briiqn.reunion.core.network.packet.protocol.console.c2s.ConsoleC2SPacket;
import dev.briiqn.reunion.core.network.packet.protocol.java.c2s.impl.JavaKeepAliveC2SPacket;
import dev.briiqn.reunion.core.session.ConsoleSession;
import io.netty.buffer.ByteBuf;
import lombok.extern.log4j.Log4j2;

@Log4j2
@PacketInfo(side = PacketSide.CONSOLE_C2S, id = 0, supports = {39, 78, 80})
public final class ConsoleKeepAliveC2SPacket extends ConsoleC2SPacket {

  // MinecraftConsoles fork: how many answers the console has actually produced. A Java server
  // drops a client that has not answered in thirty seconds, and two sessions in a row died at
  // almost exactly thirty-two - a timeout signature, not a malformed packet. Whether this number
  // ever moves is the difference between "the console is not answering" and "it answers and the
  // problem is elsewhere", and nothing in the logs could tell those apart.
  private static int answered = 0;

  private int keepAliveId;

  public ConsoleKeepAliveC2SPacket() {
  }

  @Override
  public void read(ByteBuf buf) {
    keepAliveId = buf.readInt();
  }

  @Override
  public void write(ByteBuf buf) {
    buf.writeInt(keepAliveId);
  }

  @Override
  public void handle(ConsoleSession session) {
    session.onKeepAliveReceived();

    log.info("[KEEPALIVE] console answered id={} -> forwarding to the server (answer #{})",
        keepAliveId, ++answered);

    PacketManager.sendToJava(session.getJavaSession(), new JavaKeepAliveC2SPacket(keepAliveId));
  }
}