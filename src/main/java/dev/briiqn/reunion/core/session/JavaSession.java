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

package dev.briiqn.reunion.core.session;

import dev.briiqn.reunion.core.ReunionServer;
import dev.briiqn.reunion.core.network.packet.annotation.PacketInfo;
import dev.briiqn.reunion.core.network.packet.data.Packet;
import dev.briiqn.reunion.core.network.packet.data.PacketSide;
import dev.briiqn.reunion.core.network.packet.registry.PacketRegistry;
import dev.briiqn.reunion.core.network.pipeline.java.handler.JavaChannelHandler;
import dev.briiqn.reunion.core.network.pipeline.java.handler.JavaLoginHandler;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.log4j.Log4j2;

@Getter
@Setter
@RequiredArgsConstructor
@Log4j2
public final class JavaSession {

  public static final AttributeKey<JavaSession> SESSION_KEY =
      AttributeKey.valueOf("reunion.java_session");

  public static final int JAVA_PROTOCOL = 47;

  private final ConsoleSession consoleSession;

  @Getter(AccessLevel.PUBLIC)
  private final ReunionServer server;
  private final ExecutorService packetProcessor =
      Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());
  @Getter(AccessLevel.PUBLIC)
  @Setter(AccessLevel.PUBLIC)
  private Channel javaChannel;
  @Getter
  private boolean switching = false;
  @Getter
  @Setter(AccessLevel.PUBLIC)
  private String disconnectReason = "Server closed";

  public ChannelInboundHandlerAdapter javaChannelHandler() {
    return new JavaChannelHandler(this);
  }

  // MinecraftConsoles fork: exactly ONE login handler per Java connection, owned here.
  //
  // The protocol-80 auth relay suspends the Java encryption handshake mid-flight while the LCE
  // client proves ownership of its own Mojang account, so the parked state lives on this object
  // and ConsoleAuthResponseC2SPacket has to reach the very same one to resume it.
  //
  // It used to be created twice - once here and once inside JavaChannelHandler - and that was
  // silent and fatal: the channel handler parked the encryption state on ITS copy, the auth
  // response was delivered to THIS one, and the second always had nothing in flight. The symptom
  // was a join that hung with a single "auth response with no handshake in flight" line and no
  // error anywhere. Created eagerly rather than in sendHandshake() because the channel handler is
  // built first, when the pipeline is set up.
  //
  // Created on first use rather than in a field initializer: field initializers run BEFORE the
  // generated constructor body, so `new JavaLoginHandler(this)` there would read consoleSession
  // and server while they are still null and hand every join a handler wired to nothing.
  private volatile JavaLoginHandler loginHandler;

  public JavaLoginHandler getLoginHandler() {
    JavaLoginHandler handler = loginHandler;
    if (handler == null) {
      synchronized (this) {
        handler = loginHandler;
        if (handler == null) {
          handler = new JavaLoginHandler(this);
          loginHandler = handler;
        }
      }
    }
    return handler;
  }

  public void sendHandshake(String playerName, String host, int port) {
    getLoginHandler().sendHandshake(host, port);
  }

  public void submitPacket(Runnable task) {
    packetProcessor.execute(task);
  }

  public void shutdownPacketProcessor() {
    packetProcessor.shutdown();
  }

  public void close(boolean isSwitch) {
    this.switching = isSwitch;
    if (javaChannel != null && javaChannel.isActive()) {
      javaChannel.close();
    }
  }

  public void close() {
    close(false);
  }

  public PacketSide getPacketSide(Packet packet) {
    PacketInfo info = PacketRegistry.getPacketInfo(packet.getClass());
    return info != null ? info.side() : PacketSide.JAVA_C2S;
  }
}