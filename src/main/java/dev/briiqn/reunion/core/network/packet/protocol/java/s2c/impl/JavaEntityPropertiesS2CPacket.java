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
import dev.briiqn.reunion.core.network.packet.protocol.console.s2c.impl.ConsoleUpdateAttributesS2CPacket;
import dev.briiqn.reunion.core.network.packet.protocol.java.s2c.JavaS2CPacket;
import dev.briiqn.reunion.core.session.JavaSession;
import dev.briiqn.reunion.core.util.StringUtil;
import dev.briiqn.reunion.core.util.VarIntUtil;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.extern.log4j.Log4j2;

/**
 * MinecraftConsoles fork - EntityProperties, translated into LCE's UpdateAttributesPacket.
 *
 * <p>This is the packet that tells a client how fast it may move, and it was the one thing in the
 * bridge that nothing forwarded. The consequence was visible rather than theoretical: with a Speed
 * effect the console side moved at ITS idea of boosted speed, the Java server expected ITS own, and
 * the positions the proxy relayed were rejected as impossible - the player was teleported back a
 * step at a time. The effect packet was already translated, so the console knew it was hasted; it
 * did not know by how much.
 *
 * <p>Two things do not survive the crossing unchanged, and both are lossy in one direction only:
 *
 * <ul>
 *   <li>Java names an attribute with a string, LCE with a small enum. Attributes LCE has no slot
 *       for are dropped - the client validates the id against its own set anyway and would reject
 *       them, so sending them would only cost bytes.
 *   <li>Java identifies a modifier with a 128-bit UUID, LCE with a small enum. The handful of
 *       modifiers that are the same everywhere (the potion effects, sprinting) map onto their real
 *       slot; everything else becomes ANONYMOUS. That is safe rather than a shortcut: LCE refuses a
 *       second modifier sharing a named id, and deliberately allows any number of anonymous ones,
 *       all of which contribute to the total. Mapping the unknown to a named slot is what would
 *       lose them.
 * </ul>
 *
 * <p>The operation codes need no translation: both sides number them 0 add, 1 multiply base,
 * 2 multiply total.
 */
@Log4j2
@PacketInfo(side = PacketSide.JAVA_S2C, id = 0x20, supports = {47})
public final class JavaEntityPropertiesS2CPacket extends JavaS2CPacket {

  // eATTRIBUTE_ID, from Minecraft.World/Attribute.h. Kept as literals with the enum name beside
  // them rather than as a shared constant, because the enum lives in another language and another
  // repository - a symbolic reference here would be a comfortable-looking lie.
  private static final short ATTR_MAX_HEALTH = 0;
  private static final short ATTR_FOLLOW_RANGE = 1;
  private static final short ATTR_KNOCKBACK_RESISTANCE = 2;
  private static final short ATTR_MOVEMENT_SPEED = 3;
  private static final short ATTR_ATTACK_DAMAGE = 4;
  private static final short ATTR_HORSE_JUMP_STRENGTH = 5;
  private static final short ATTR_ZOMBIE_SPAWN_REINFORCEMENTS = 6;

  // eMODIFIER_ID, same file.
  private static final int MOD_ANONYMOUS = 0;
  private static final int MOD_MOB_SPRINTING = 3;
  private static final int MOD_POTION_DAMAGEBOOST = 8;
  private static final int MOD_POTION_HEALTHBOOST = 9;
  private static final int MOD_POTION_MOVESPEED = 10;
  private static final int MOD_POTION_MOVESLOWDOWN = 11;
  private static final int MOD_POTION_WEAKNESS = 12;

  /**
   * Attribute keys as they appear on protocol 47. Both spellings are accepted: 1.8 uses the
   * camelCase names, and the snake_case ones arrive if anything upstream has already renamed them.
   */
  private static final Map<String, Short> ATTRIBUTE_IDS = new HashMap<>();

  /** The modifier UUIDs that mean the same thing on both sides. Everything else is anonymous. */
  private static final Map<String, Integer> MODIFIER_IDS = new HashMap<>();

  static {
    ATTRIBUTE_IDS.put("generic.maxhealth", ATTR_MAX_HEALTH);
    ATTRIBUTE_IDS.put("generic.max_health", ATTR_MAX_HEALTH);
    ATTRIBUTE_IDS.put("generic.followrange", ATTR_FOLLOW_RANGE);
    ATTRIBUTE_IDS.put("generic.follow_range", ATTR_FOLLOW_RANGE);
    ATTRIBUTE_IDS.put("generic.knockbackresistance", ATTR_KNOCKBACK_RESISTANCE);
    ATTRIBUTE_IDS.put("generic.knockback_resistance", ATTR_KNOCKBACK_RESISTANCE);
    ATTRIBUTE_IDS.put("generic.movementspeed", ATTR_MOVEMENT_SPEED);
    ATTRIBUTE_IDS.put("generic.movement_speed", ATTR_MOVEMENT_SPEED);
    ATTRIBUTE_IDS.put("generic.attackdamage", ATTR_ATTACK_DAMAGE);
    ATTRIBUTE_IDS.put("generic.attack_damage", ATTR_ATTACK_DAMAGE);
    ATTRIBUTE_IDS.put("horse.jumpstrength", ATTR_HORSE_JUMP_STRENGTH);
    ATTRIBUTE_IDS.put("horse.jump_strength", ATTR_HORSE_JUMP_STRENGTH);
    ATTRIBUTE_IDS.put("zombie.spawnreinforcements", ATTR_ZOMBIE_SPAWN_REINFORCEMENTS);
    ATTRIBUTE_IDS.put("zombie.spawn_reinforcements", ATTR_ZOMBIE_SPAWN_REINFORCEMENTS);

    MODIFIER_IDS.put("91aeaa56-376b-4498-935b-2f7f68070635", MOD_POTION_MOVESPEED);
    MODIFIER_IDS.put("7107de5e-7ce8-4030-940e-514c1f160890", MOD_POTION_MOVESLOWDOWN);
    MODIFIER_IDS.put("662a6b8d-da3e-4c1c-8813-96ea6097278d", MOD_MOB_SPRINTING);
    MODIFIER_IDS.put("648d7064-6a60-4f59-8abe-c2c23a6dd7a9", MOD_POTION_DAMAGEBOOST);
    MODIFIER_IDS.put("22653b89-116e-49dc-9b6b-9971489b5be5", MOD_POTION_WEAKNESS);
    MODIFIER_IDS.put("5d6f0ba2-1186-46ac-b896-c61c5cee99cc", MOD_POTION_HEALTHBOOST);
  }

  private int entityId;
  private final List<ConsoleUpdateAttributesS2CPacket.Snapshot> translated = new ArrayList<>();

  public JavaEntityPropertiesS2CPacket() {
  }

  @Override
  public void read(ByteBuf buf) {
    entityId = VarIntUtil.read(buf);
    int count = buf.readInt();

    // The whole packet must be consumed even when nothing in it is useful to us: the frame is
    // decoded as a unit, and an attribute we skip still has its bytes on the wire.
    for (int i = 0; i < count && buf.isReadable(); i++) {
      String key = StringUtil.readJavaString(buf);
      double base = buf.readDouble();
      int modifierCount = VarIntUtil.read(buf);

      List<ConsoleUpdateAttributesS2CPacket.Modifier> modifiers = new ArrayList<>(modifierCount);
      for (int m = 0; m < modifierCount && buf.isReadable(); m++) {
        long msb = buf.readLong();
        long lsb = buf.readLong();
        double amount = buf.readDouble();
        byte operation = buf.readByte();

        Integer id = MODIFIER_IDS.get(new java.util.UUID(msb, lsb).toString());
        modifiers.add(new ConsoleUpdateAttributesS2CPacket.Modifier(
            id == null ? MOD_ANONYMOUS : id, amount, operation));
      }

      Short attributeId = ATTRIBUTE_IDS.get(key.toLowerCase(Locale.ROOT));
      if (attributeId != null) {
        translated.add(new ConsoleUpdateAttributesS2CPacket.Snapshot(attributeId, base, modifiers));
      }
    }
  }

  @Override
  public void handle(JavaSession session) {
    if (translated.isEmpty()) {
      return;
    }

    Integer consoleId = session.getConsoleSession().getEntityManager().getConsoleId(entityId);
    if (consoleId == null) {
      return;
    }

    // Only this player's own movement speed is worth reporting: it is the one the server
    // validates our position against.
    if (entityId == session.getConsoleSession().getJavaEntityId()) {
      for (ConsoleUpdateAttributesS2CPacket.Snapshot snapshot : translated) {
        if (snapshot.attributeId() == ATTR_MOVEMENT_SPEED) {
          double value = effectiveValue(snapshot);
          session.getConsoleSession().setServerMovementSpeed(value);
          log.info("[MOVE-SERVER] attributes: base={} -> value={} ({} modifier(s))",
              snapshot.base(), value, snapshot.modifiers().size());
        }
      }
    }

    PacketManager.sendToConsole(session.getConsoleSession(),
        new ConsoleUpdateAttributesS2CPacket(consoleId, translated));
  }

  /**
   * The standard attribute formula: additions first, then the two multiplications. Reproduced here
   * only to have a number to print - the client does its own arithmetic and is the one that counts.
   */
  private static double effectiveValue(ConsoleUpdateAttributesS2CPacket.Snapshot snapshot) {
    double value = snapshot.base();
    for (ConsoleUpdateAttributesS2CPacket.Modifier m : snapshot.modifiers()) {
      if (m.operation() == 0) {
        value += m.amount();
      }
    }
    double base = value;
    for (ConsoleUpdateAttributesS2CPacket.Modifier m : snapshot.modifiers()) {
      if (m.operation() == 1) {
        value += base * m.amount();
      }
    }
    for (ConsoleUpdateAttributesS2CPacket.Modifier m : snapshot.modifiers()) {
      if (m.operation() == 2) {
        value *= 1.0 + m.amount();
      }
    }
    return value;
  }
}
