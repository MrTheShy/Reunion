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

import dev.briiqn.reunion.api.world.Dimension;
import dev.briiqn.reunion.api.world.Location;
import dev.briiqn.reunion.core.network.packet.manager.PacketManager;
import dev.briiqn.reunion.core.network.packet.protocol.console.c2s.ConsoleC2SPacket;
import dev.briiqn.reunion.core.network.packet.protocol.java.c2s.impl.JavaPlayerC2SPacket;
import dev.briiqn.reunion.core.network.packet.protocol.java.c2s.impl.JavaPlayerLookC2SPacket;
import dev.briiqn.reunion.core.network.packet.protocol.java.c2s.impl.JavaPlayerPositionC2SPacket;
import dev.briiqn.reunion.core.network.packet.protocol.java.c2s.impl.JavaPlayerPositionLookC2SPacket;
import dev.briiqn.reunion.core.plugin.hooks.PluginEventHooks;
import dev.briiqn.reunion.core.plugin.session.ConsoleSessionPlayerAdapter;
import dev.briiqn.reunion.core.session.ConsoleSession;
import dev.briiqn.reunion.core.util.math.vector.Vec2f;
import dev.briiqn.reunion.core.util.math.vector.Vec3d;
import io.netty.buffer.ByteBuf;
import lombok.Getter;

@lombok.extern.log4j.Log4j2
public abstract class ConsolePlayerFlyingC2SPacket extends ConsoleC2SPacket {

  /** Rate limiter for the movement log. Approximate on purpose - it only paces a print. */
  private static int MOVE_LOG_COUNTER = 0;

  @Getter
  protected Vec3d pos = Vec3d.ZERO;
  @Getter
  protected double stance = 0.0;
  @Getter
  protected Vec2f rot = Vec2f.ZERO;
  @Getter
  protected boolean onGround;
  @Getter
  protected boolean hasPos, hasRot;

  public double getX() {
    return pos.x();
  }

  public double getY() {
    return pos.y();
  }

  public double getZ() {
    return pos.z();
  }

  public float getYaw() {
    return rot.yaw();
  }

  public float getPitch() {
    return rot.pitch();
  }

  @Override
  public void write(ByteBuf buf) {
  }

  @Override
  public void handle(ConsoleSession session) {

    if (session.isWaitingForInitialTeleport()) {
      session.noteMovementStall("waiting-initial-teleport", hasPos, hasRot);
      return;
    }

    if (hasPos) {
      double px = pos.x(), pz = pos.z();
      if (Math.abs(px) > 2.9999999E7D || Math.abs(pz) > 2.9999999E7D) {
        return;
      }
      session.markClientScreenReady();
    }

    final Vec3d lastPos = session.getLastPos();
    final Vec2f lastRot = session.getLastRot();

    double javaX = hasPos ? session.toJavaX(pos.x()) : lastPos.x();
    double javaY = hasPos ? pos.y() : lastPos.y();
    double javaZ = hasPos ? session.toJavaZ(pos.z()) : lastPos.z();
    float newYaw = hasRot ? rot.yaw() : lastRot.yaw();
    float newPitch = hasRot ? rot.pitch() : lastRot.pitch();

    if (session.hasPendingTeleport()) {
      if (hasPos && hasRot) {
        Vec3d coords = session.consumePendingTeleport();
        Vec2f teleportRot = lastRot;
        session.setLastPos(new Vec3d(javaX, javaY, javaZ));
        session.setLastRot(new Vec2f(newYaw, newPitch));
        session.getPendingTeleportAcks().incrementAndGet();
        PacketManager.sendToJava(session.getJavaSession(),
            new JavaPlayerPositionLookC2SPacket(
                coords.x(), coords.y(), coords.z(),
                teleportRot.yaw(), teleportRot.pitch(),
                false));
      } else {
        PacketManager.sendToJava(session.getJavaSession(), new JavaPlayerC2SPacket(onGround));
      }
      // Either way this packet's own position did not reach the server: the first case answered
      // with the TELEPORT's coordinates, the second with no position at all.
      session.noteMovementStall("pending-teleport", hasPos, hasRot);
      return;
    }

    // MinecraftConsoles fork: this branch is where movement was being lost, and the diagnostic
    // said so outright - every stall sampled reported branch=teleport-ack with an EMPTY teleport
    // queue, so the queue was never the problem (an earlier attempt at a fix assumed it was, and
    // was reverted). The counter alone was doing it, and it went 2 -> 4 -> 7 rather than draining.
    //
    // Two reasons it could not drain:
    //
    //  * The decrement was gated on hasPos || hasRot. Three of the five sampled packets had
    //    NEITHER - a bare flying packet carries only onGround - so they passed through this branch
    //    consuming a slot's worth of the player's movement while clearing nothing.
    //  * While suppressing we answer with a packet that has no position in it. The server's view
    //    of the player therefore never advances, so it keeps correcting, and every correction adds
    //    another ack. The suppression was feeding the thing it was waiting on.
    //
    // The counter still exists and still does its job: it is a grace period so the client's first
    // few post-teleport packets, which still report the OLD position, are not mistaken for the
    // player refusing the teleport. What changes is that it now always drains - one slot per
    // packet, whatever that packet carries - and that it can no longer run unbounded.
    //
    // The cap is the part that matters most, and it is deliberately not clever. Whatever else is
    // or is not understood about this handshake, a client cannot be prevented from moving for more
    // than one second: past that the grace period is abandoned and the player's real position goes
    // through. Being wrong here should cost a hiccup, not eight seconds of paralysis.
    if (session.getPendingTeleportAcks().get() > 0) {
      if (session.getMovementStallPackets() >= ConsoleSession.MAX_SWALLOWED_PACKETS) {
        log.warn("[MOVE-STALL] grace period abandoned after {} packet(s), {} ack(s) outstanding - "
                + "forwarding the player's real position",
            session.getMovementStallPackets(), session.getPendingTeleportAcks().get());
        session.getPendingTeleportAcks().set(0);
        // and fall through to the normal path below
      } else {
        session.getPendingTeleportAcks().decrementAndGet();
        PacketManager.sendToJava(session.getJavaSession(), new JavaPlayerC2SPacket(onGround));
        session.noteMovementStall("teleport-ack", hasPos, hasRot);
        return;
      }
    }

    if (hasPos || hasRot) {
      Location from = Location.of(
          lastPos.x(), lastPos.y(), lastPos.z(),
          lastRot.yaw(), lastRot.pitch(),
          Dimension.fromIdOrDefault(session.getDimension()));
      Location to = Location.of(javaX, javaY, javaZ, newYaw, newPitch,
          Dimension.fromIdOrDefault(session.getDimension()));

      if (!PluginEventHooks.fireMove(new ConsoleSessionPlayerAdapter(session), from, to)) {
        session.noteMovementStall("plugin-denied-move", hasPos, hasRot);
        return;
      }
    }

    if (hasPos) {
      // MinecraftConsoles fork: the measured half of the movement telemetry. This is the distance
      // the console client actually travelled between two position packets; the client prints the
      // speed it believes it has (see LocalPlayer::aiStep) and the server's own figures are logged
      // above. Put the three on one line and a disagreement stops being a guess.
      //
      // Every twentieth packet, and only while moving: enough to watch, not enough to bury the log.
      double dx = javaX - lastPos.x();
      double dz = javaZ - lastPos.z();
      double moved = Math.sqrt(dx * dx + dz * dz);
      if (moved > 0.001 && (++MOVE_LOG_COUNTER % 20) == 0) {
        log.info("[MOVE-PROXY] moved={} per packet | server attr={} abilitiesWalk={} y={}",
            String.format("%.5f", moved),
            String.format("%.5f", session.getServerMovementSpeed()),
            String.format("%.5f", session.getServerWalkingSpeed()),
            String.format("%.3f", javaY));
      }

      session.checkWorldBounds(javaX, javaZ);
      session.setLastPos(new Vec3d(javaX, javaY, javaZ));
    }

    // Past every early return: this packet is going to the server as itself.
    session.noteMovementForwarded();

    boolean rotationChanged = false;
    if (hasRot) {
      Vec2f newRot = new Vec2f(newYaw, newPitch);
      rotationChanged = newRot.hasChanged(lastRot);
      session.setLastRot(newRot);
    }

    if (hasPos || (!hasPos && !hasRot)) {
      session.flushTickActions();
    }

    if (hasPos && hasRot) {
      if (rotationChanged) {
        PacketManager.sendToJava(session.getJavaSession(),
            new JavaPlayerPositionLookC2SPacket(javaX, javaY, javaZ, newYaw, newPitch, onGround));
      } else {
        PacketManager.sendToJava(session.getJavaSession(),
            new JavaPlayerPositionC2SPacket(javaX, javaY, javaZ, onGround));
      }
    } else if (hasPos) {
      PacketManager.sendToJava(session.getJavaSession(),
          new JavaPlayerPositionC2SPacket(javaX, javaY, javaZ, onGround));
    } else if (hasRot) {
      if (rotationChanged) {
        PacketManager.sendToJava(session.getJavaSession(),
            new JavaPlayerLookC2SPacket(newYaw, newPitch, onGround));
      } else {
        PacketManager.sendToJava(session.getJavaSession(), new JavaPlayerC2SPacket(onGround));
      }
    } else {
      PacketManager.sendToJava(session.getJavaSession(), new JavaPlayerC2SPacket(onGround));
    }
  }
}