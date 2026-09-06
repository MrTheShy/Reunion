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
package dev.briiqn.reunion.core.util.game;

import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import lombok.extern.log4j.Log4j2;

/**
 * MinecraftConsoles fork - SRV resolution for Java Edition addresses.
 *
 * <p>A Java client typing {@code mc.hypixel.net} does not simply connect to that name on the port
 * it was given: it first asks DNS for {@code _minecraft._tcp.mc.hypixel.net}, and a large share of
 * public servers publish their real endpoint only that way. Without this the proxy dials the bare
 * A record on 25565 and every such server looks "offline" for no visible reason.
 *
 * <p>Two rules matter, and both come from how the vanilla client behaves:
 *
 * <ul>
 *   <li>SRV is consulted ONLY when the player did not name a port. An explicit port is an explicit
 *       endpoint and must win, or myserver.net:25570 would be silently redirected somewhere else.
 *   <li>The address that goes in the HANDSHAKE stays the one the player typed, never the SRV
 *       target. Virtual-host routing (BungeeCord, Velocity, and Hypixel's own front end) keys on
 *       that string, so substituting the resolved name is how you get "unknown host" back from a
 *       server that is answering perfectly well.
 * </ul>
 *
 * <p>A lookup failure is not an error: most addresses have no SRV record at all, and the right
 * answer for them is "use what you were given".
 */
@Log4j2
public final class SrvResolver {

  /** How long a resolution stays good for. DNS TTLs are deliberately ignored - see resolve(). */
  private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

  private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

  private SrvResolver() {
  }

  /** A resolved endpoint: where to connect, and what to say in the handshake. */
  public record Endpoint(String connectHost, int connectPort, String handshakeHost,
                         int handshakePort) {

    public boolean isRedirected() {
      return !connectHost.equalsIgnoreCase(handshakeHost) || connectPort != handshakePort;
    }
  }

  private record CacheEntry(Endpoint endpoint, long expiresAt) {
  }

  /**
   * Resolves an address the way a vanilla client would.
   *
   * @param host what the player typed
   * @param port the port to use when there is no SRV record
   * @param portWasExplicit true when the player really did name a port, which disables SRV
   */
  public static Endpoint resolve(String host, int port, boolean portWasExplicit) {
    Endpoint identity = new Endpoint(host, port, host, port);

    if (portWasExplicit || host == null || host.isEmpty()) {
      return identity;
    }
    // An IP literal has no SRV record by construction, and asking DNS about one only buys a
    // timeout on networks with a slow resolver.
    if (looksLikeIpLiteral(host)) {
      return identity;
    }

    String key = host.toLowerCase();
    long now = System.currentTimeMillis();
    CacheEntry cached = CACHE.get(key);
    if (cached != null && cached.expiresAt() > now) {
      Endpoint e = cached.endpoint();
      // The cache holds only the SRV answer. The handshake half always comes from THIS call, so a
      // second lookup of the same host cannot inherit the first one's port.
      return new Endpoint(e.connectHost(), e.connectPort(), host, port);
    }

    Endpoint resolved = identity;
    try {
      Hashtable<String, String> env = new Hashtable<>();
      env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
      // Without a timeout, a resolver that black-holes the query parks the join for the JVM
      // default, which is minutes: the player would see the game hang rather than fail.
      env.put("com.sun.jndi.dns.timeout.initial", "2000");
      env.put("com.sun.jndi.dns.timeout.retries", "2");

      InitialDirContext ctx = new InitialDirContext(env);
      try {
        Attributes attrs = ctx.getAttributes("_minecraft._tcp." + host, new String[] {"SRV"});
        Attribute srv = attrs.get("srv");
        if (srv != null && srv.size() > 0) {
          // Records read "priority weight port target". Lowest priority wins; ties are meant to be
          // broken by weight, but every record here points into one operator's network and taking
          // the first keeps a join deterministic, which matters far more when debugging one.
          String best = null;
          int bestPriority = Integer.MAX_VALUE;
          for (int i = 0; i < srv.size(); i++) {
            String rec = String.valueOf(srv.get(i)).trim();
            String[] parts = rec.split("\\s+");
            if (parts.length < 4) {
              continue;
            }
            int priority = Integer.parseInt(parts[0]);
            if (priority < bestPriority) {
              bestPriority = priority;
              best = rec;
            }
          }
          if (best != null) {
            String[] parts = best.split("\\s+");
            int targetPort = Integer.parseInt(parts[2]);
            String target = parts[3];
            if (target.endsWith(".")) {
              target = target.substring(0, target.length() - 1);
            }
            if (!target.isEmpty() && !".".equals(target)) {
              resolved = new Endpoint(target, targetPort, host, port);
              log.info("[SRV] {} resolves to {}:{}", host, target, targetPort);
            }
          }
        }
      } finally {
        ctx.close();
      }
    } catch (NamingException e) {
      // The normal case for an address with no SRV record. Debug, not warn: at a visible level
      // this would make every ordinary join look like it went wrong.
      log.debug("[SRV] no record for {} ({})", host, e.getMessage());
    } catch (RuntimeException e) {
      log.debug("[SRV] lookup of {} failed: {}", host, e.toString());
    }

    // The negative answer is cached too. That is the point of the cache, since it is the common
    // one, and re-asking would add a DNS round trip to every single connection.
    CACHE.put(key, new CacheEntry(resolved, now + CACHE_TTL_MS));
    return resolved;
  }

  public static void invalidate(String host) {
    if (host != null) {
      CACHE.remove(host.toLowerCase());
    }
  }

  private static boolean looksLikeIpLiteral(String host) {
    if (host.indexOf(':') >= 0) {
      return true; // IPv6
    }
    // IPv4 if every character is a digit or a dot and it ends in a digit: "1.2.3.4" yes,
    // "mc.hypixel.net" no.
    if (!Character.isDigit(host.charAt(host.length() - 1))) {
      return false;
    }
    for (int i = 0; i < host.length(); i++) {
      char c = host.charAt(i);
      if (c != '.' && !Character.isDigit(c)) {
        return false;
      }
    }
    return true;
  }
}
