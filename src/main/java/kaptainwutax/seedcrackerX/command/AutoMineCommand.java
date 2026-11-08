package kaptainwutax.seedcrackerX.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.LinkedHashSet;
import java.util.Set;
import kaptainwutax.seedcrackerX.SeedCracker;
import kaptainwutax.seedcrackerX.automine.AutoMineManager;
import kaptainwutax.seedcrackerX.xray.OreXrayManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;

public class AutoMineCommand extends ClientCommand {
   @Override
   public String getName() {
      return "zd";
   }

   @Override
   public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
      builder.executes(context -> this.openGui());
      builder.then(
         ClientCommands.argument("ores", StringArgumentType.greedyString()).executes(context -> this.startMulti(StringArgumentType.getString(context, "ores")))
      );
      builder.then(ClientCommands.literal("停止").executes(context -> this.stop()));
   }

   private int openGui() {
      SeedCracker.get().getDataStorage().openAutoMineGui = true;
      return 0;
   }

   private AutoMineManager.Task parseTask(String name) {
      switch (name.trim()) {
         case "钻石":
            return AutoMineManager.Task.DIAMOND;
         case "远古残骸":
         case "残骸":
         case "下界合金":
            return AutoMineManager.Task.DEBRIS;
         case "铁":
         case "铁矿":
            return AutoMineManager.Task.IRON;
         case "金":
         case "金矿":
            return AutoMineManager.Task.GOLD;
         case "煤":
         case "煤矿":
         case "煤炭":
            return AutoMineManager.Task.COAL;
         case "红石":
         case "红石矿":
            return AutoMineManager.Task.REDSTONE;
         case "青金石":
         case "青金":
         case "lapiz":
            return AutoMineManager.Task.LAPIS;
         case "铜":
         case "铜矿":
            return AutoMineManager.Task.COPPER;
         case "绿宝石":
         case "翡翠":
            return AutoMineManager.Task.EMERALD;
         default:
            return null;
      }
   }

   private int startMulti(String oreNames) {
      if (!FabricLoader.getInstance().isModLoaded("baritone")) {
         sendFeedback("[自动挖矿] 未检测到 Baritone，请将 Baritone jar 放入 mods 文件夹。", ChatFormatting.RED);
         return 0;
      }

      if (!OreXrayManager.INSTANCE.isActive()) {
         sendFeedback("[自动挖矿] 提示：尚未用 /ov sz 设置种子，自动挖矿需要种子预测矿物位置；pro 破译出种子后点击 [直接设置] 即可。", ChatFormatting.YELLOW);
      }

      Set<AutoMineManager.Task> tasks = new LinkedHashSet<>();
      StringBuilder invalid = new StringBuilder();

      for (String part : oreNames.trim().split("\\s+")) {
         if (!part.isEmpty()) {
            AutoMineManager.Task t = this.parseTask(part);
            if (t != null) {
               tasks.add(t);
            } else {
               if (invalid.length() > 0) {
                  invalid.append("、");
               }

               invalid.append(part);
            }
         }
      }

      if (tasks.isEmpty()) {
         sendFeedback("[自动挖矿] 未识别到有效矿物：" + invalid + "。可用：钻石/远古残骸/铁/金/煤/红石/青金石/铜/绿宝石", ChatFormatting.RED);
         return 0;
      }

      if (invalid.length() > 0) {
         sendFeedback("[自动挖矿] 已忽略无效矿物：" + invalid, ChatFormatting.YELLOW);
      }

      AutoMineManager.INSTANCE.start(tasks);
      return 0;
   }

   private int stop() {
      AutoMineManager.INSTANCE.stop();
      return 0;
   }
}
