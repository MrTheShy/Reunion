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

package dev.briiqn.reunion;

import dev.briiqn.reunion.core.ReunionServer;
import dev.briiqn.reunion.core.control.McConsolesControlChannel;
import lombok.extern.log4j.Log4j2;

@Log4j2
public final class Main {

  public static void main(String[] args) throws Exception {
    log.info("Starting Reunion...");
    ReunionServer server = new ReunionServer();
    Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "reunion-shutdown"));

    // MinecraftConsoles fork: when Minecraft.Client launched us it passes --mcc-control-port=N and
    // is already listening on it. Connect AFTER start() so the proxy.ready we send is true - the
    // game takes it as "you may now join". Without the flag nothing here runs and the proxy is
    // exactly the standalone one upstream ships.
    int controlPort = McConsolesControlChannel.parsePort(args);

    server.start();

    if (controlPort > 0) {
      McConsolesControlChannel.connect(controlPort, server);
    }
  }
}
