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
import java.util.List;

/**
 * MinecraftConsoles fork - LCE UpdateAttributesPacket (id 44).
 *
 * <p>Carries an entity's attribute values and their modifiers, which is how the client learns how
 * fast it is allowed to move. Without it the console side falls back to its own idea of what a
 * Speed effect does, that idea differs from the Java server's, and the positions it then reports
 * are rejected as impossible - the player is pulled back one step at a time.
 *
 * <p>Wire format, matching Minecraft.World/UpdateAttributesPacket.cpp:
 *
 * <pre>
 *   int   entityId
 *   int   attributeCount
 *     short  attributeId      (an index into eATTRIBUTE_ID, not a name)
 *     double baseValue
 *     short  modifierCount
 *       int    modifierId     (an index into eMODIFIER_ID, not a UUID)
 *       double amount
 *       byte   operation      (0 add, 1 multiply base, 2 multiply total)
 * </pre>
 *
 * <p>Both ids are small enums here where Java uses a string and a 128-bit UUID; the translation
 * lives in {@code JavaEntityPropertiesS2CPacket}, which is the only thing that builds this.
 */
@PacketInfo(side = PacketSide.CONSOLE_S2C, id = 44, supports = {39, 78, 80})
public final class ConsoleUpdateAttributesS2CPacket extends ConsoleS2CPacket {

  /** One attribute and the modifiers applied to it, already translated into LCE's enums. */
  public record Snapshot(short attributeId, double base, List<Modifier> modifiers) {
  }

  /** One modifier, already translated. */
  public record Modifier(int modifierId, double amount, byte operation) {
  }

  private final int entityId;
  private final List<Snapshot> attributes;

  public ConsoleUpdateAttributesS2CPacket() {
    this(0, List.of());
  }

  public ConsoleUpdateAttributesS2CPacket(int entityId, List<Snapshot> attributes) {
    this.entityId = entityId;
    this.attributes = attributes;
  }

  @Override
  public void write(ByteBuf buf) {
    buf.writeInt(entityId);
    buf.writeInt(attributes.size());

    for (Snapshot attribute : attributes) {
      buf.writeShort(attribute.attributeId());
      buf.writeDouble(attribute.base());
      buf.writeShort(attribute.modifiers().size());

      for (Modifier modifier : attribute.modifiers()) {
        buf.writeInt(modifier.modifierId());
        buf.writeDouble(modifier.amount());
        buf.writeByte(modifier.operation());
      }
    }
  }
}
