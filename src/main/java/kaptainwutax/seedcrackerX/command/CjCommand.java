package kaptainwutax.seedcrackerX.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;

public class CjCommand extends ClientCommand {
   @Override
   public String getName() {
      return "cj";
   }

   @Override
   public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
      builder.executes(c -> this.detect());
   }

   private int detect() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.player != null && mc.getConnection() != null) {
         ClientPacketListener conn = mc.getConnection();
         String brand = this.getServerBrand(conn);
         ServerData sd = mc.getCurrentServer();
         String address = "未知";
         int ping = -1;
         if (sd != null) {
            address = sd.ip;

            try {
               ping = (int)sd.ping;
            } catch (Throwable var11) {
            }
         }

         sendFeedback("========== 服务器插件检测 ==========", ChatFormatting.GOLD);
         if (brand != null && !brand.isEmpty()) {
            sendFeedback("服务器类型: " + brand, ChatFormatting.AQUA);
         } else {
            sendFeedback("服务器类型: 未知（可能为原版服务端）", ChatFormatting.GRAY);
         }

         sendFeedback("服务器地址: " + address, ChatFormatting.AQUA);
         if (ping >= 0) {
            sendFeedback("延迟: " + ping + " ms", ChatFormatting.AQUA);
         }

         try {
            conn.sendCommand("plugins");
            sendFeedback("已发送 /plugins 指令，请查看下方服务器返回的插件列表", ChatFormatting.GREEN);
         } catch (Throwable t) {
            try {
               conn.sendChat("/plugins");
               sendFeedback("已发送 /plugins 指令，请查看下方服务器返回的插件列表", ChatFormatting.GREEN);
            } catch (Throwable t2) {
               sendFeedback("发送 /plugins 失败，请手动输入 /plugins 查看", ChatFormatting.RED);
            }
         }

         sendFeedback("如果服务器未返回插件列表，可能是权限不足，可尝试:", ChatFormatting.YELLOW);
         sendFeedback("  /pl  /plugin list  /version", ChatFormatting.GRAY);
         if (brand != null) {
            brand = brand.toLowerCase();
            if (brand.contains("paper")) {
               sendFeedback("检测到 Paper 服务端 — 常见插件: EssentialsX, Vault, LuckPerms, PlaceholderAPI", ChatFormatting.LIGHT_PURPLE);
            } else if (brand.contains("spigot")) {
               sendFeedback("检测到 Spigot 服务端 — 常见插件: Essentials, Vault, PermissionsEx", ChatFormatting.LIGHT_PURPLE);
            } else if (brand.contains("craftbukkit")) {
               sendFeedback("检测到 CraftBukkit 服务端", ChatFormatting.LIGHT_PURPLE);
            } else if (brand.contains("fabric")) {
               sendFeedback("检测到 Fabric 服务端 — 使用 Fabric Mod 而非插件", ChatFormatting.LIGHT_PURPLE);
            } else if (brand.contains("forge")) {
               sendFeedback("检测到 Forge 服务端 — 使用 Forge Mod 而非插件", ChatFormatting.LIGHT_PURPLE);
            } else if (brand.contains("vanilla") || brand.contains("native")) {
               sendFeedback("检测到原版服务端 — 无插件", ChatFormatting.LIGHT_PURPLE);
            }
         }

         return 0;
      } else {
         sendFeedback("[插件检测] 请先连接到服务器", ChatFormatting.RED);
         return 1;
      }
   }

   private String getServerBrand(ClientPacketListener conn) {
      try {
         Method method = conn.getClass().getMethod("getServerBrand");
         return (String)method.invoke(conn);
      } catch (Throwable var8) {
         try {
            for (Class<?> c = conn.getClass(); c != null; c = c.getSuperclass()) {
               try {
                  Field f = c.getDeclaredField("serverBrand");
                  f.setAccessible(true);
                  return (String)f.get(conn);
               } catch (NoSuchFieldException var6) {
               }
            }
         } catch (Throwable var7) {
         }

         try {
            for (Class<?> c = conn.getClass(); c != null; c = c.getSuperclass()) {
               try {
                  Field f = c.getDeclaredField("brand");
                  f.setAccessible(true);
                  return (String)f.get(conn);
               } catch (NoSuchFieldException var4) {
               }
            }
         } catch (Throwable var5) {
         }

         return null;
      }
   }
}
