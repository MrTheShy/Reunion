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

import dev.briiqn.reunion.core.network.packet.annotation.PacketInfo;
import dev.briiqn.reunion.core.network.packet.data.PacketSide;
import dev.briiqn.reunion.core.network.packet.protocol.console.s2c.ConsoleS2CPacket;
import io.netty.buffer.ByteBuf;

/**
 * MinecraftConsoles fork - LCE PlayerAbilitiesPacket (id 202), server to client.
 *
 * <p>Carries the other half of "how fast may I move": where UpdateAttributes covers effects and
 * modifiers, this carries the flat walking and flying speeds, plus whether flight is allowed and
 * currently on. A lobby that hands out flight or a speed boost usually does it here rather than
 * with a potion, so without this the console side moves at its built-in speed while the server
 * expects something else - the same disagreement that shows up as being dragged backwards.
 *
 * <p>The layout is identical to protocol 47's, field for field and bit for bit
 * (Minecraft.World/PlayerAbilitiesPacket.cpp): a flags byte with invulnerable / flying / can-fly /
 * instabuild in bits 0 to 3, then flyingSpeed and walkingSpeed as floats. Nothing is translated;
 * only the packet id differs.
 */
@PacketInfo(side = PacketSide.CONSOLE_S2C, id = 202, supports = {39, 78, 80})
public final class ConsolePlayerAbilitiesS2CPacket extends ConsoleS2CPacket {

  private final byte flags;
  private final float flyingSpeed;
  private final float walkingSpeed;

  public ConsolePlayerAbilitiesS2CPacket() {
    this((byte) 0, 0.05f, 0.1f);
  }

  public ConsolePlayerAbilitiesS2CPacket(byte flags, float flyingSpeed, float walkingSpeed) {
    this.flags = flags;
    this.flyingSpeed = flyingSpeed;
    this.walkingSpeed = walkingSpeed;
  }

  @Override
  public void write(ByteBuf buf) {
    buf.writeByte(flags);
    buf.writeFloat(flyingSpeed);
    buf.writeFloat(walkingSpeed);
  }
}
