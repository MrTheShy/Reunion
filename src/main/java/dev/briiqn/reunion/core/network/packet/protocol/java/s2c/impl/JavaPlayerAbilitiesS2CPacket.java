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
import dev.briiqn.reunion.core.network.packet.protocol.console.s2c.impl.ConsolePlayerAbilitiesS2CPacket;
import dev.briiqn.reunion.core.network.packet.protocol.java.s2c.JavaS2CPacket;
import dev.briiqn.reunion.core.session.JavaSession;
import io.netty.buffer.ByteBuf;

/**
 * MinecraftConsoles fork - PlayerAbilities, forwarded to the console client.
 *
 * <p>Nothing forwarded this, and it is the second of the two packets that tell a client how fast
 * it may move. UpdateAttributes carries the effects; this carries the flat walking and flying
 * speeds and whether flight is allowed. Lobbies hand out flight and speed boosts through exactly
 * this packet rather than through potions, so a console player got neither the flight nor the
 * speed the server had granted - and moved at a speed the server was not expecting.
 *
 * <p>A pure forward: protocol 47 and LCE agree on the layout and on the meaning of every flag bit,
 * so translating would only be an opportunity to introduce a difference.
 */
@PacketInfo(side = PacketSide.JAVA_S2C, id = 0x39, supports = {47})
public final class JavaPlayerAbilitiesS2CPacket extends JavaS2CPacket {

  private byte flags;
  private float flyingSpeed;
  private float walkingSpeed;

  public JavaPlayerAbilitiesS2CPacket() {
  }

  @Override
  public void read(ByteBuf buf) {
    flags = buf.readByte();
    flyingSpeed = buf.readFloat();
    walkingSpeed = buf.readFloat();
  }

  @Override
  public void handle(JavaSession session) {
    PacketManager.sendToConsole(session.getConsoleSession(),
        new ConsolePlayerAbilitiesS2CPacket(flags, flyingSpeed, walkingSpeed));
  }
}
