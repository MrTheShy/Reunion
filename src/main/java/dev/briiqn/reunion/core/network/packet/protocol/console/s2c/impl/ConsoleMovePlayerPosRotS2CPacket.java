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

    // The second field is the EYE height here, and that is not a mistake even though the client
    // puts its FEET in the same field when it sends. The two directions genuinely disagree, the
    // way the 1.7-era Java protocol did.
    //
    // Entity::setPos is what settles it: bb->set(..., y - heightOffset, ...), so player->y is the
    // eye position and bb->y0 the feet. On the way in, ClientConnection::handleMovePlayer feeds
    // this field straight into absMoveTo, which lands in player->y - eye space. On the way out it
    // fills the same field with bb->y0 - feet.
    //
    // Do not "fix" this by matching the two directions. It was tried: swapping them puts every
    // teleport 1.62 blocks too low and the player spawns inside the floor.
    buf.writeDouble(position.x());
    buf.writeDouble(stanceY);
    buf.writeDouble(feetY);
    buf.writeDouble(position.z());
    buf.writeFloat(yaw);
    buf.writeFloat(pitch);
    buf.writeByte(onGround);
  }
}