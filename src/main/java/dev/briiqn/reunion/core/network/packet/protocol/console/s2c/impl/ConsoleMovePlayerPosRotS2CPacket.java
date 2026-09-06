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
import dev.briiqn.reunion.core.util.math.vector.Vec3d;
import io.netty.buffer.ByteBuf;


@PacketInfo(side = PacketSide.CONSOLE_S2C, id = 13, supports = {39, 78})
public final class ConsoleMovePlayerPosRotS2CPacket extends ConsoleS2CPacket {

  public static final double EYE_HEIGHT = 1.62f;

  private final Vec3d position;
  private final float yaw;
  private final float pitch;
  private final byte onGround;

  public ConsoleMovePlayerPosRotS2CPacket() {
    this(Vec3d.ZERO, 0f, 0f, (byte) 1);
  }


  public ConsoleMovePlayerPosRotS2CPacket(Vec3d position, float yaw, float pitch, byte onGround) {
    this.position = position;
    this.yaw = yaw;
    this.pitch = pitch;
    this.onGround = onGround;
  }


  @Deprecated
  public ConsoleMovePlayerPosRotS2CPacket(double x, double stance, double y, double z,
      float yaw, float pitch, byte onGround) {
    this(new Vec3d(x, y, z), yaw, pitch, onGround);
  }

  public Vec3d position() {
    return position;
  }

  public float yaw() {
    return yaw;
  }

  public float pitch() {
    return pitch;
  }

  public byte onGround() {
    return onGround;
  }

  @Override
  public void write(ByteBuf buf) {
    double feetY = position.y();
    double stanceY = feetY + EYE_HEIGHT;

    // MinecraftConsoles fork: feet in the second field, eyes in the third - the order the client
    // actually reads, which is the same one it uses when it sends.
    //
    // These two were the other way round, so every teleport placed the player 1.62 blocks above
    // where the server had put them. The client then fell, reported a position the server had not
    // authorised, and was corrected - which teleported it 1.62 blocks up again. Measured on a live
    // session before the fix: 297 teleports in 196 seconds, with the correction distance sitting
    // at 1.31 to 2.16 blocks, centred on the eye height plus whatever horizontal movement had
    // happened in between. It also accounts for the reported "flash somewhere else and snap back".
    //
    // The client's own handler settles the order beyond argument: it assigns the second field
    // straight into the position it moves to, and when it answers it puts the bounding box floor
    // back in that same field and the eye position in the third.
    buf.writeDouble(position.x());
    buf.writeDouble(feetY);
    buf.writeDouble(stanceY);
    buf.writeDouble(position.z());
    buf.writeFloat(yaw);
    buf.writeFloat(pitch);
    buf.writeByte(onGround);
  }
}