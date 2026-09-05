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
import dev.briiqn.reunion.core.network.packet.protocol.console.c2s.ConsoleC2SPacket;
import dev.briiqn.reunion.core.session.ConsoleSession;
import dev.briiqn.reunion.core.util.StringUtil;
import io.netty.buffer.ByteBuf;
import lombok.extern.log4j.Log4j2;

/**
 * MinecraftConsoles fork - protocol 80 only. The client's answer to
 * {@code ConsoleAuthSchemeS2CPacket}: it has posted to sessionserver/join with its OWN access
 * token, so the suspended Java encryption handshake can be resumed.
 *
 * <p>Mirrors AuthResponsePacket (id 171) in Minecraft.World: UTF chosenScheme, UTF mojangUuid,
 * UTF username.
 */
@Log4j2
@PacketInfo(side = PacketSide.CONSOLE_C2S, id = 171, supports = {80})
public final class ConsoleAuthResponseC2SPacket extends ConsoleC2SPacket {

  private String chosenScheme = "";
  private String mojangUuid = "";
  private String username = "";

  public ConsoleAuthResponseC2SPacket() {
  }

  @Override
  public void read(ByteBuf buf) {
    chosenScheme = StringUtil.readConsoleUtf(buf);
    mojangUuid = StringUtil.readConsoleUtf(buf);
    username = StringUtil.readConsoleUtf(buf);
  }

  @Override
  public void write(ByteBuf buf) {
    // client -> server only; same as every other ConsoleC2SPacket
  }

  @Override
  public void handle(ConsoleSession session) {
    // The uuid the client authenticated with must be the one it announced at login. They can only
    // differ if the player switched account mid-handshake or if somebody is replaying a response
    // onto another session; either way, resuming would tell the Java server to expect a name that
    // nobody has proven ownership of.
    String announced = session.getLceClientMojangUuid();
    if (announced != null && !announced.isEmpty()
        && !announced.equalsIgnoreCase(mojangUuid)) {
      log.error("[Auth-Relay] uuid mismatch for '{}': login said {}, auth response said {}",
          session.getPlayerName(), announced, mojangUuid);
      session.getConsoleChannel().close();
      return;
    }

    var java = session.getJavaSession();
    if (java == null || java.getLoginHandler() == null) {
      log.warn("[Auth-Relay] auth response from '{}' but no Java login handler is in flight",
          session.getPlayerName());
      return;
    }

    java.getLoginHandler().onConsoleAuthResponse(chosenScheme, username);
  }
}
