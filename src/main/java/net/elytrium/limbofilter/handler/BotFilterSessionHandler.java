/*
 * Copyright (C) 2021 - 2022 Elytrium
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.elytrium.limbofilter.handler;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.proxy.protocol.MinecraftPacket;
import com.velocitypowered.proxy.protocol.packet.ClientSettings;
import com.velocitypowered.proxy.protocol.packet.PluginMessage;
import com.velocitypowered.proxy.protocol.util.PluginMessageUtil;
import java.text.MessageFormat;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.chunk.VirtualChunk;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.elytrium.limboapi.api.protocol.PreparedPacket;
import net.elytrium.limboapi.api.protocol.packets.BuiltInPackets;
import net.elytrium.limbofilter.LimboFilter;
import net.elytrium.limbofilter.Settings;
import net.elytrium.limbofilter.cache.CachedPackets;
import net.elytrium.limbofilter.captcha.CaptchaHolder;
import net.elytrium.limbofilter.stats.Statistics;

public class BotFilterSessionHandler implements LimboSessionHandler {

  private static final double[] LOADED_CHUNK_SPEED_CACHE = new double[Settings.IMP.MAIN.FALLING_CHECK_TICKS];
  private static long FALLING_CHECK_TOTAL_TIME;

  private final Player proxyPlayer;
  private final ProtocolVersion version;
  private final LimboFilter plugin;
  private final Statistics statistics;
  private final CachedPackets packets;

  private final MinecraftPacket fallingCheckPos;
  private final MinecraftPacket fallingCheckChunk;
  private final MinecraftPacket fallingCheckView;

  private final int validX;
  private final int validY;
  private final int validZ;
  private final int validTeleportId;

  private double posX;
  private double posY;
  private double lastY;
  private double posZ;
  private int waitingTeleportId;
  private boolean onGround;

  private int ticks = 1;
  private int ignoredTicks;

  private long joinTime;
  private ScheduledTask filterMainTask;

  private CheckState state;
  private LimboPlayer player;
  private Limbo server;
  private String captchaAnswer;
  private int attempts = Settings.IMP.MAIN.CAPTCHA_ATTEMPTS;
  private int nonValidPacketsSize;
  private boolean startedListening;
  private boolean checkedBySettings;
  private boolean checkedByBrand;

  public BotFilterSessionHandler(Player proxyPlayer, LimboFilter plugin) {
    this.proxyPlayer = proxyPlayer;
    this.version = this.proxyPlayer.getProtocolVersion();
    this.plugin = plugin;

    this.statistics = this.plugin.getStatistics();
    this.packets = this.plugin.getPackets();

    ThreadLocalRandom random = ThreadLocalRandom.current();
    this.validX = random.nextInt(256, 16384);
    // See https://media.discordapp.net/attachments/878241549857738793/915165038464098314/unknown.png
    this.validY = random.nextInt(256 + (this.version.compareTo(ProtocolVersion.MINECRAFT_1_8) < 0 ? 250 : 0), 512);
    this.validZ = random.nextInt(256, 16384);
    this.validTeleportId = random.nextInt(65535);

    this.posX = this.validX;
    this.posY = this.validY;
    this.posZ = this.validZ;

    this.state = plugin.checkCpsLimit(Settings.IMP.MAIN.FILTER_AUTO_TOGGLE.CHECK_STATE_TOGGLE)
        ? CheckState.valueOf(Settings.IMP.MAIN.CHECK_STATE)
        : CheckState.valueOf(Settings.IMP.MAIN.CHECK_STATE_NON_TOGGLED);

    Settings.MAIN.COORDS coords = Settings.IMP.MAIN.COORDS;
    this.fallingCheckPos = this.createPlayerPosAndLook(
        this.plugin.getFactory(),
        this.validX, this.validY, this.validZ,
        (float) (this.state == CheckState.CAPTCHA_POSITION ? coords.CAPTCHA_YAW : coords.FALLING_CHECK_YAW),
        (float) (this.state == CheckState.CAPTCHA_POSITION ? coords.CAPTCHA_PITCH : coords.FALLING_CHECK_PITCH)
    );
    this.fallingCheckChunk = this.createChunkData(
        this.plugin.getFactory(), this.plugin.getFactory().createVirtualChunk(this.validX >> 4, this.validZ >> 4)
    );
    this.fallingCheckView = this.createUpdateViewPosition(this.plugin.getFactory(), this.validX, this.validZ);
  }

  @Override
  public void onSpawn(Limbo server, LimboPlayer player) {
    this.server = server;
    this.player = player;

    this.joinTime = System.currentTimeMillis();
    if (this.state == CheckState.ONLY_CAPTCHA) {
      this.sendCaptcha();
    } else if (this.state == CheckState.CAPTCHA_POSITION) {
      this.sendFallingCheckPackets();
      this.sendCaptcha();
    } else if (this.state == CheckState.ONLY_POSITION || this.state == CheckState.CAPTCHA_ON_POSITION_FAILED) {
      if (this.proxyPlayer.getProtocolVersion().compareTo(ProtocolVersion.MINECRAFT_1_8) >= 0) {
        if (!Settings.IMP.MAIN.STRINGS.CHECKING_TITLE.isEmpty() && !Settings.IMP.MAIN.STRINGS.CHECKING_SUBTITLE.isEmpty()) {
          this.player.writePacket(this.packets.getCheckingTitle());
        }
      }
      this.player.writePacket(this.packets.getCheckingChat());
      this.sendFallingCheckPackets();
    }

    this.player.flushPackets();

    this.filterMainTask = this.plugin.getServer().getScheduler().buildTask(this.plugin, () -> {
      if (System.currentTimeMillis() - this.joinTime > this.getTimeout()) {
        this.disconnect(this.packets.getTimesUp(), true);
      }
    }).delay(1, TimeUnit.SECONDS).repeat(1, TimeUnit.SECONDS).schedule();
  }

  private void sendFallingCheckPackets() {
    this.player.writePacket(this.fallingCheckPos);

    ProtocolVersion playerVersion = this.proxyPlayer.getProtocolVersion();
    if (playerVersion.compareTo(ProtocolVersion.MINECRAFT_1_14) >= 0) {
      this.player.writePacket(this.fallingCheckView);
    }

    if (playerVersion.compareTo(ProtocolVersion.MINECRAFT_1_17) < 0) {
      this.player.writePacket(this.fallingCheckChunk);
    }
  }

  @Override
  public void onMove(double x, double y, double z) {
    if (this.version.compareTo(ProtocolVersion.MINECRAFT_1_8) <= 0
        && x == this.validX && y == this.validY && z == this.validZ && this.waitingTeleportId == this.validTeleportId) {
      this.ticks = 1;
      this.posY = -1;
      this.waitingTeleportId = -1;
    }

    this.posX = x;
    this.lastY = this.posY;
    this.posY = y;
    this.posZ = z;

    if (Settings.IMP.MAIN.FALLING_CHECK_DEBUG) {
      this.logPosition();
    }
    if (!this.startedListening && this.state != CheckState.ONLY_CAPTCHA) {
      if (this.posX == this.validX && this.posZ == this.validZ) {
        this.startedListening = true;
      }
      if (this.nonValidPacketsSize > Settings.IMP.MAIN.NON_VALID_POSITION_XZ_ATTEMPTS) {
        this.fallingCheckFailed("A lot of non-valid XZ attempts");
        return;
      }

      this.lastY = this.validY;
      ++this.nonValidPacketsSize;
    }
    if (this.startedListening && this.state != CheckState.SUCCESSFUL) {
      if (this.lastY == Settings.IMP.MAIN.COORDS.CAPTCHA_Y || this.onGround) {
        return;
      }
      if (this.state == CheckState.ONLY_CAPTCHA) {
        if (this.lastY != this.posY && this.waitingTeleportId == -1) {
          this.setCaptchaPositionAndDisableFalling();
        }
        return;
      }
      if (this.lastY - this.posY == 0) {
        ++this.ignoredTicks;
        return;
      }
      if (this.ticks >= Settings.IMP.MAIN.FALLING_CHECK_TICKS) {
        if (this.state == CheckState.CAPTCHA_POSITION) {
          this.changeStateToCaptcha();
        } else {
          this.finishCheck();
        }
        return;
      }
      if (this.ignoredTicks > Settings.IMP.MAIN.NON_VALID_POSITION_Y_ATTEMPTS) {
        this.fallingCheckFailed("A lot of non-valid Y attempts");
        return;
      }
      if ((this.posX != this.validX && this.posZ != this.validZ) || this.checkY()) {
        this.fallingCheckFailed("Non-valid X, Z or Velocity");
        return;
      }
      PreparedPacket expBuf = this.packets.getExperience().get(this.ticks);
      if (expBuf != null) {
        this.player.writePacketAndFlush(expBuf);
      }

      ++this.ticks;
    }
  }

  private void fallingCheckFailed(String reason) {
    if (Settings.IMP.MAIN.FALLING_CHECK_DEBUG) {
      LimboFilter.getLogger().info(reason);
      this.logPosition();
    }

    if (this.state == CheckState.CAPTCHA_ON_POSITION_FAILED) {
      List<PreparedPacket> expList = this.packets.getExperience();
      this.player.writePacketAndFlush(expList.get(expList.size() - 1));
      this.changeStateToCaptcha();
    } else {
      this.disconnect(this.packets.getFallingCheckFailed(), true);
    }
  }

  private void logPosition() {
    LimboFilter.getLogger().info(
        "lastY=" + this.lastY + "; y=" + this.posY + "; diff=" + (this.lastY - this.posY) + "; need=" + getLoadedChunkSpeed(this.ticks)
        + "; x=" + this.posX + "; z=" + this.posZ + "; validX=" + this.validX  + "; validY=" + this.validY + "; validZ=" + this.validZ
        + "; ticks=" + this.ticks + "; ignoredTicks=" + this.ignoredTicks + "; state=" + this.state
    );
  }

  private boolean checkY() {
    return Math.abs(this.lastY - this.posY - getLoadedChunkSpeed(this.ticks)) > Settings.IMP.MAIN.MAX_VALID_POSITION_DIFFERENCE;
  }

  @Override
  public void onGround(boolean onGround) {
    this.onGround = onGround;
  }

  @Override
  public void onTeleport(int teleportId) {
    if (teleportId == this.waitingTeleportId) {
      this.ticks = 1;
      this.posY = -1;
      this.lastY = -1;
      this.waitingTeleportId = -1;
    }
  }

  @Override
  public void onChat(String message) {
    if (this.state == CheckState.CAPTCHA_POSITION || this.state == CheckState.ONLY_CAPTCHA) {
      if (message.equals(this.captchaAnswer)) {
        this.player.writePacketAndFlush(this.packets.getResetSlot());
        this.finishCheck();
      } else if (--this.attempts != 0) {
        this.sendCaptcha();
      } else {
        this.disconnect(this.packets.getCaptchaFailed(), true);
      }
    }
  }

  @Override
  public void onGeneric(Object packet) {
    if (packet instanceof PluginMessage) {
      PluginMessage pluginMessage = (PluginMessage) packet;
      if (PluginMessageUtil.isMcBrand(pluginMessage) && !this.checkedByBrand) {
        String brand = PluginMessageUtil.readBrandMessage(pluginMessage.content());
        LimboFilter.getLogger().info("{} has client brand {}", this.proxyPlayer, brand);
        if (!Settings.IMP.MAIN.BLOCKED_CLIENT_BRANDS.contains(brand)) {
          this.checkedByBrand = true;
        }
      }
    } else if (packet instanceof ClientSettings) {
      if (Settings.IMP.MAIN.CHECK_CLIENT_SETTINGS && !this.checkedBySettings) {
        this.checkedBySettings = true;
      }
    }
  }

  @Override
  public void onDisconnect() {
    this.filterMainTask.cancel();
  }

  private void finishCheck() {
    if (System.currentTimeMillis() - this.joinTime < FALLING_CHECK_TOTAL_TIME && this.state != CheckState.ONLY_CAPTCHA) {
      if (this.state == CheckState.CAPTCHA_POSITION && this.ticks < Settings.IMP.MAIN.FALLING_CHECK_TICKS) {
        this.state = CheckState.ONLY_POSITION;
      } else {
        if (this.state == CheckState.CAPTCHA_ON_POSITION_FAILED) {
          this.changeStateToCaptcha();
        } else {
          this.disconnect(this.packets.getFallingCheckFailed(), true);
        }
      }
      return;
    }

    if (Settings.IMP.MAIN.CHECK_CLIENT_SETTINGS && !this.checkedBySettings) {
      this.disconnect(this.packets.getKickClientCheckSettings(), true);
      return;
    }

    if (Settings.IMP.MAIN.CHECK_CLIENT_BRAND && !this.checkedByBrand) {
      this.disconnect(this.packets.getKickClientCheckBrand(), true);
      return;
    }

    this.state = CheckState.SUCCESSFUL;
    this.plugin.cacheFilterUser(this.proxyPlayer);

    if (this.plugin.checkCpsLimit(Settings.IMP.MAIN.FILTER_AUTO_TOGGLE.ONLINE_MODE_VERIFY)
        || this.plugin.checkCpsLimit(Settings.IMP.MAIN.FILTER_AUTO_TOGGLE.NEED_TO_RECONNECT)) {
      this.disconnect(this.packets.getSuccessfulBotFilterDisconnect(), false);
    } else {
      this.player.writePacketAndFlush(this.packets.getSuccessfulBotFilterChat());
      this.player.disconnect();
    }
  }

  private void changeStateToCaptcha() {
    this.state = CheckState.ONLY_CAPTCHA;
    //this.joinTime = System.currentTimeMillis() + this.fallingCheckTotalTime;
    this.setCaptchaPositionAndDisableFalling();
    if (this.captchaAnswer == null) {
      this.sendCaptcha();
    }
  }

  private void setCaptchaPositionAndDisableFalling() {
    this.server.respawnPlayer(this.proxyPlayer);
    this.player.writePacketAndFlush(this.packets.getNoAbilities());

    this.waitingTeleportId = this.validTeleportId;
  }

  private void sendCaptcha() {
    ProtocolVersion version = this.proxyPlayer.getProtocolVersion();
    CaptchaHolder captchaHolder = this.plugin.getCachedCaptcha().randomCaptcha();
    this.captchaAnswer = captchaHolder.getAnswer();
    Settings.MAIN.STRINGS strings = Settings.IMP.MAIN.STRINGS;
    if (this.attempts == Settings.IMP.MAIN.CAPTCHA_ATTEMPTS) {
      this.player.writePacket(
          this.packets.createChatPacket(this.plugin.getFactory(), MessageFormat.format(strings.CHECKING_CAPTCHA_CHAT, this.attempts))
      );
      if (version.compareTo(ProtocolVersion.MINECRAFT_1_8) >= 0) {
        if (!strings.CHECKING_CAPTCHA_TITLE.isEmpty() && !strings.CHECKING_CAPTCHA_SUBTITLE.isEmpty()) {
          this.player.writePacket(
              this.packets.createTitlePacket(
                  this.plugin.getFactory(),
                  MessageFormat.format(strings.CHECKING_CAPTCHA_TITLE, this.attempts),
                  MessageFormat.format(strings.CHECKING_CAPTCHA_SUBTITLE, this.attempts)
              )
          );
        }
      }
    } else {
      this.player.writePacket(
          this.packets.createChatPacket(this.plugin.getFactory(), MessageFormat.format(strings.CHECKING_WRONG_CAPTCHA_CHAT, this.attempts))
      );
    }
    this.player.writePacket(this.packets.getSetSlot());
    for (Object packet : captchaHolder.getMapPacket(version)) {
      this.player.writePacket(packet);
    }

    this.player.flushPackets();
  }

  private void disconnect(PreparedPacket reason, boolean blocked) {
    this.player.closeWith(reason);
    if (blocked) {
      this.statistics.addBlockedConnection();
    }
  }

  private int getTimeout() {
    if (this.proxyPlayer.getRemoteAddress().getPort() == 0) {
      return Settings.IMP.MAIN.GEYSER_TIME_OUT;
    } else {
      return Settings.IMP.MAIN.TIME_OUT;
    }
  }

  private MinecraftPacket createChunkData(LimboFactory factory, VirtualChunk chunk) {
    chunk.setSkyLight(chunk.getX() & 15, 256, chunk.getZ() & 15, (byte) 1);
    return (MinecraftPacket) factory.instantiatePacket(
        BuiltInPackets.ChunkData, chunk.getFullChunkSnapshot(), true, this.plugin.getFilterWorld().getDimension().getMaxSections()
    );
  }

  private MinecraftPacket createPlayerPosAndLook(LimboFactory factory, double x, double y, double z, float yaw, float pitch) {
    return (MinecraftPacket) factory.instantiatePacket(BuiltInPackets.PlayerPositionAndLook, x, y, z, yaw, pitch, 44, false, true);
  }

  private MinecraftPacket createUpdateViewPosition(LimboFactory factory, int x, int z) {
    return (MinecraftPacket) factory.instantiatePacket(BuiltInPackets.UpdateViewPosition, x >> 4, z >> 4);
  }

  static {
    for (int i = 0; i < Settings.IMP.MAIN.FALLING_CHECK_TICKS; ++i) {
      LOADED_CHUNK_SPEED_CACHE[i] = -((Math.pow(0.98, i) - 1) * 3.92);
    }
  }

  public static double getLoadedChunkSpeed(int ticks) {
    if (ticks == -1) {
      return 0;
    }

    return LOADED_CHUNK_SPEED_CACHE[ticks];
  }

  public static void setFallingCheckTotalTime(long time) {
    FALLING_CHECK_TOTAL_TIME = time;
  }

  public enum CheckState {

    ONLY_POSITION,
    ONLY_CAPTCHA,
    CAPTCHA_POSITION,
    CAPTCHA_ON_POSITION_FAILED,
    SUCCESSFUL
  }
}
