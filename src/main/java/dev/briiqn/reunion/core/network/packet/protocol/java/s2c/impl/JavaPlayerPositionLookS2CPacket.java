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
import dev.briiqn.reunion.core.network.packet.protocol.console.s2c.impl.ConsoleMovePlayerPosRotS2CPacket;
import dev.briiqn.reunion.core.network.packet.protocol.java.s2c.JavaS2CPacket;
import dev.briiqn.reunion.core.session.ConsoleSession;
import dev.briiqn.reunion.core.session.JavaSession;
import dev.briiqn.reunion.core.util.math.vector.Vec2f;
import dev.briiqn.reunion.core.util.math.vector.Vec3d;
import io.netty.buffer.ByteBuf;
import lombok.extern.log4j.Log4j2;

@Log4j2
@PacketInfo(side = PacketSide.JAVA_S2C, id = 0x08, supports = {47})
public final class JavaPlayerPositionLookS2CPacket extends JavaS2CPacket {

  private double x, y, z;
  private float yaw, pitch;
  private byte flags;

  public JavaPlayerPositionLookS2CPacket() {
  }

  @Override
  public void read(ByteBuf buf) {
    x = buf.readDouble();
    y = buf.readDouble();
    z = buf.readDouble();
    yaw = buf.readFloat();
    pitch = buf.readFloat();
    flags = buf.readByte();
  }

  @Override
  public void handle(JavaSession session) {
    ConsoleSession cs = session.getConsoleSession();

    Vec3d lastPos = cs.getLastPos();
    Vec2f lastRot = cs.getLastRot();

    double resolvedX = ((flags & 0x01) != 0) ? x + lastPos.x() : x;
    double resolvedY = ((flags & 0x02) != 0) ? y + lastPos.y() : y;
    double resolvedZ = ((flags & 0x04) != 0) ? z + lastPos.z() : z;
    float resolvedYaw = ((flags & 0x08) != 0) ? yaw + lastRot.yaw() : yaw;
    float resolvedPit = ((flags & 0x10) != 0) ? pitch + lastRot.pitch() : pitch;

    cs.checkWorldBounds(resolvedX, resolvedZ);

    cs.setLastPos(new Vec3d(resolvedX, resolvedY, resolvedZ));
    cs.setLastRot(new Vec2f(resolvedYaw, resolvedPit));

    double consoleX = cs.toConsoleX(resolvedX);
    double consoleZ = cs.toConsoleZ(resolvedZ);
    double stance = resolvedY + 1.62f;

    if (cs.isWaitingForInitialTeleport()) {
      cs.setWorldOffsetX(((int) resolvedX) >> 4 << 4);
      cs.setWorldOffsetZ(((int) resolvedZ) >> 4 << 4);
      cs.setWaitingForInitialTeleport(false);
    }

    // MinecraftConsoles fork: every teleport the server sends, with where it came from.
    //
    // Nothing logged these, which left the most visible symptom unmeasurable: a flash to somewhere
    // else - the death lobby, say - and straight back. That is two positions in quick succession,
    // and without a record of what the server actually asked for there is no way to tell a server
    // that sent two teleports from a proxy that answered the first one wrongly and provoked the
    // second. The distance from the previous position is printed because a large jump followed by a
    // small correction is the signature of the second case.
    double jump = Math.sqrt(
        Math.pow(resolvedX - lastPos.x(), 2)
            + Math.pow(resolvedY - lastPos.y(), 2)
            + Math.pow(resolvedZ - lastPos.z(), 2));
    log.info("[MOVE-TP] server moved us to {} {} {} (flags=0x{}, {} blocks from where we were, "
            + "queue now {})",
        String.format("%.2f", resolvedX), String.format("%.2f", resolvedY),
        String.format("%.2f", resolvedZ), Integer.toHexString(flags & 0xFF),
        String.format("%.2f", jump), cs.pendingTeleportCount() + 1);

    cs.storePendingTeleport(resolvedX, resolvedY, resolvedZ);

    PacketManager.sendToConsole(cs, new ConsoleMovePlayerPosRotS2CPacket(
        consoleX, stance, resolvedY, consoleZ, resolvedYaw, resolvedPit, (byte) 1));
  }
}