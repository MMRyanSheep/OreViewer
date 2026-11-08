package kaptainwutax.seedcrackerX.command;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.seedfinding.mccore.version.MCVersion;
import java.util.Iterator;
import kaptainwutax.seedcrackerX.config.Config;
import kaptainwutax.seedcrackerX.xray.OreXrayManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;

public class SeedCommand extends ClientCommand {
   @Override
   public String getName() {
      return "sz";
   }

   @Override
   public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
      builder.then(
         ((RequiredArgumentBuilder)ClientCommands.argument("seed", LongArgumentType.longArg())
               .executes(context -> this.setSeed(LongArgumentType.getLong(context, "seed"), null)))
            .then(
               ClientCommands.argument("version", StringArgumentType.greedyString())
                  .executes(context -> this.setSeed(LongArgumentType.getLong(context, "seed"), StringArgumentType.getString(context, "version")))
            )
      );
   }

   private int setSeed(long seed, String versionInput) {
      String versionNote = "";
      if (versionInput != null && !versionInput.isBlank()) {
         MCVersion v = parseVersion(versionInput);
         if (v == null) {
            sendFeedback("无法识别的版本：" + versionInput.trim() + "，种子仍会设置，版本沿用当前配置", ChatFormatting.GOLD);
            sendFeedback("支持 1.8 ~ 最新版本的全部版本号，以及 26.x（例：1.16.5 / 1.18.2 / 1.20.1 / 1.21.4 / 26.2）", ChatFormatting.GRAY);
         } else {
            Config.get().setVersion(v);
            versionNote = "，版本：" + versionInput.trim() + "（世界算法基准 " + displayName(v) + "）";
         }
      }

      OreXrayManager.INSTANCE.setSeed(seed);
      Config c = Config.get();
      c.activeSeed = seed;
      c.activeVersion = versionInput != null && !versionInput.isBlank() && parseVersion(versionInput) != null ? versionInput.trim() : c.activeVersion;
      boolean exists = false;
      Iterator<Config.SeedEntry> it = c.seedHistory.iterator();

      while (it.hasNext()) {
         Config.SeedEntry e = it.next();
         if (e.seed == seed) {
            e.version = c.activeVersion;
            it.remove();
            exists = true;
            break;
         }
      }

      String label = "种子 " + seed;
      Config.SeedEntry entry = new Config.SeedEntry(seed, c.activeVersion, label);
      c.seedHistory.add(0, entry);

      while (c.seedHistory.size() > 20) {
         c.seedHistory.remove(c.seedHistory.size() - 1);
      }

      Config.save();
      sendFeedback("种子已设置：" + seed + versionNote, ChatFormatting.GREEN);
      sendFeedback("矿物透视已开启（种子已保存，下次启动自动加载）", ChatFormatting.AQUA);
      sendFeedback("结构/生物群系指引（/ov dx、/ov mdc）将按该版本推算", ChatFormatting.GRAY);
      return 0;
   }

   public static MCVersion parseVersion(String input) {
      String v = input.trim().toLowerCase().replace(" ", "");
      if (v.startsWith("v") && v.length() > 1 && Character.isDigit(v.charAt(1))) {
         v = v.substring(1);
      }

      if (v.isEmpty()) {
         return null;
      }

      if (v.equals("latest")) {
         return MCVersion.latest();
      }

      if (v.startsWith("26.")) {
         return MCVersion.v1_21_3;
      }

      String enumName = "v" + v.replace('.', '_');

      for (MCVersion mv : MCVersion.values()) {
         if (mv.name().equalsIgnoreCase(enumName)) {
            return mv;
         }
      }

      String[] parts = v.split("\\.");
      if (parts.length >= 2 && parts[0].equals("1")) {
         int minor;
         int patch;
         try {
            minor = Integer.parseInt(parts[1]);
            patch = parts.length > 2 ? Integer.parseInt(parts[2]) : -1;
         } catch (NumberFormatException e) {
            return null;
         }

         if (minor > 21) {
            return MCVersion.v1_21_3;
         }

         MCVersion best = null;
         MCVersion lowest = null;
         int bestPatch = -1;
         int lowestPatch = Integer.MAX_VALUE;

         for (MCVersion mv : MCVersion.values()) {
            String[] p = mv.name().split("_");
            if (p.length >= 2 && p[0].equalsIgnoreCase("v1")) {
               int mvMinor;
               int mvPatch;
               try {
                  mvMinor = Integer.parseInt(p[1]);
                  mvPatch = p.length > 2 ? Integer.parseInt(p[2]) : 0;
               } catch (NumberFormatException e) {
                  continue;
               }

               if (mvMinor == minor) {
                  if (mvPatch < lowestPatch) {
                     lowest = mv;
                     lowestPatch = mvPatch;
                  }

                  if (patch >= 0 && mvPatch <= patch && mvPatch > bestPatch) {
                     best = mv;
                     bestPatch = mvPatch;
                  }
               }
            }
         }

         return best != null ? best : lowest;
      } else {
         return null;
      }
   }

   private static String displayName(MCVersion v) {
      if (v == MCVersion.v1_21) {
         return "1.21.0";
      } else if (v == MCVersion.v1_21_1) {
         return "1.21.1";
      } else if (v == MCVersion.v1_21_2) {
         return "1.21.2";
      } else {
         return v == MCVersion.v1_21_3 ? "1.21.3+（含 26.x）" : v.name.replaceFirst("^v", "").replace('_', '.');
      }
   }
}
