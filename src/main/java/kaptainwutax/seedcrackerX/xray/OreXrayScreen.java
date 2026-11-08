package kaptainwutax.seedcrackerX.xray;

import com.seedfinding.mccore.version.MCVersion;
import java.util.ArrayList;
import java.util.List;
import kaptainwutax.seedcrackerX.autoeat.AutoEatManager;
import kaptainwutax.seedcrackerX.command.SeedCommand;
import kaptainwutax.seedcrackerX.config.Config;
import kaptainwutax.seedcrackerX.guidance.GuidanceManager;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class OreXrayScreen {
   public static Screen create(Screen parent) {
      OreXrayManager m = OreXrayManager.INSTANCE;
      int[] switchToIdx = new int[]{-1};
      boolean[] deleteSelected = new boolean[]{false};
      ConfigBuilder builder = ConfigBuilder.create()
         .setParentScreen(parent)
         .setTitle(Component.translatable("xs.title").withStyle(ChatFormatting.AQUA))
         .setDefaultBackgroundTexture(Identifier.parse("minecraft:textures/block/deepslate_diamond_ore.png"))
         .setTransparentBackground(true)
         .setSavingRunnable(() -> {
            if (deleteSelected[0] && switchToIdx[0] >= 0 && switchToIdx[0] < Config.get().seedHistory.size()) {
               Config.get().seedHistory.remove(switchToIdx[0]);
               Config.save();
            }

            if (!deleteSelected[0] && switchToIdx[0] >= 0 && switchToIdx[0] < Config.get().seedHistory.size()) {
               Config.SeedEntry e = Config.get().seedHistory.get(switchToIdx[0]);
               m.setSeed(e.seed);
               Config.get().activeSeed = e.seed;
               Config.get().activeVersion = e.version != null ? e.version : "";
               if (!e.version.isEmpty()) {
                  MCVersion v = SeedCommand.parseVersion(e.version);
                  if (v != null) {
                     Config.get().setVersion(v);
                  }
               }

               Config.save();
            }

            m.saveSettings();
         });
      ConfigEntryBuilder eb = builder.entryBuilder();
      ConfigCategory overworld = builder.getOrCreateCategory(Component.translatable("xs.overworld"));
      overworld.addEntry(eb.startBooleanToggle(Component.translatable("xs.diamond"), m.diamond.get()).setSaveConsumer(m.diamond::set).build());
      overworld.addEntry(eb.startBooleanToggle(Component.translatable("xs.iron"), m.iron.get()).setSaveConsumer(m.iron::set).build());
      overworld.addEntry(eb.startBooleanToggle(Component.translatable("xs.gold"), m.gold.get()).setSaveConsumer(m.gold::set).build());
      overworld.addEntry(eb.startBooleanToggle(Component.translatable("xs.coal"), m.coal.get()).setSaveConsumer(m.coal::set).build());
      overworld.addEntry(eb.startBooleanToggle(Component.translatable("xs.redstone"), m.redstone.get()).setSaveConsumer(m.redstone::set).build());
      overworld.addEntry(eb.startBooleanToggle(Component.translatable("xs.lapis"), m.lapis.get()).setSaveConsumer(m.lapis::set).build());
      overworld.addEntry(eb.startBooleanToggle(Component.translatable("xs.copper"), m.copper.get()).setSaveConsumer(m.copper::set).build());
      overworld.addEntry(eb.startBooleanToggle(Component.literal("绿宝石矿"), m.emerald.get()).setSaveConsumer(m.emerald::set).build());
      ConfigCategory nether = builder.getOrCreateCategory(Component.translatable("xs.nether"));
      nether.addEntry(eb.startBooleanToggle(Component.translatable("xs.ancientDebris"), m.ancientDebris.get()).setSaveConsumer(m.ancientDebris::set).build());
      ConfigCategory settings = builder.getOrCreateCategory(Component.translatable("xs.settings"));
      settings.addEntry(
         eb.startIntSlider(Component.translatable("xs.renderDistance"), m.renderDistance, 1, 16).setSaveConsumer(val -> m.renderDistance = val).build()
      );
      settings.addEntry(
         eb.startBooleanToggle(Component.translatable("xs.antiXray"), m.antiXray)
            .setTooltip(Component.translatable("xs.antiXray.tooltip"))
            .setSaveConsumer(val -> {
               m.antiXray = val;
               m.clearCache();
            })
            .build()
      );
      settings.addEntry(
         eb.startBooleanToggle(Component.translatable("xs.guidance"), Config.get().guidanceEnabled)
            .setTooltip(Component.translatable("xs.guidance.tooltip"))
            .setSaveConsumer(val -> {
               Config.get().guidanceEnabled = val;
               if (!val) {
                  GuidanceManager.INSTANCE.clear();
               }

               Config.save();
            })
            .build()
      );
      settings.addEntry(
         eb.startBooleanToggle(Component.literal("包满自动存盒（潜影盒）"), Config.get().autoMineShulkerSave)
            .setTooltip(
               Component.literal(
                  "开启后：背包 36 格全满、且背包里有未满的潜影盒时，自动把鞘翅、矿物及可堆叠物品存入盒内腾出格子。\n"
                     + "走纯原版交互：临时把潜影盒放在身边 → 打开 → 存入 → 挖回 → 捡回，无需安装 QuickShulker，但需要附近有一格空地。\n"
                     + "工具、食物、穿戴装备、潜影盒本身不会被存入；任何一步失败都会尝试把盒子挖回来。\n"
                     + "自动收集鞘翅或自动挖矿期间会暂停操作，存完自动继续。也可用 /os ch 快速开关。"
               )
            )
            .setSaveConsumer(val -> {
               Config.get().autoMineShulkerSave = val;
               Config.save();
            })
            .build()
      );
      settings.addEntry(
         eb.startBooleanToggle(Component.literal("饿肚子自动吃食物"), Config.get().autoEatEnabled)
            .setTooltip(
               Component.literal(
                  "开启后：饱食度达到阈值时自动从背包 36 格里挑最合适的食物（优先刚好补满、不浪费），\n"
                     + "必要时临时把食物换到快捷栏，吃完后把快捷栏还原；走纯原版交互，无需服务端模组。\n"
                     + "不会自动吃蜘蛛眼、河豚、毒马铃薯、紫颂果、谜之炖菜；金苹果/腐肉等只在快饿死时才考虑。\n"
                     + "打开界面、玩家正在右键/挖掘、自动存盒/自动挖矿/鞘翅收集运行期间会暂停。也可用 /os eat 开关。"
               )
            )
            .setSaveConsumer(val -> {
               Config.get().autoEatEnabled = val;
               if (!val) {
                  AutoEatManager.INSTANCE.cancel();
               }

               Config.save();
            })
            .build()
      );
      settings.addEntry(
         eb.startIntSlider(Component.literal("自动进食饱食度阈值"), AutoEatManager.threshold(), 1, 19)
            .setTooltip(Component.literal("饱食度 ≤ 该值时触发自动进食。默认 18（保持自然回血所需饱食度），范围 1..19。"))
            .setSaveConsumer(val -> {
               Config.get().autoEatThreshold = val;
               Config.save();
            })
            .build()
      );
      settings.addEntry(
         eb.startBooleanToggle(Component.literal("自动打怪"), Config.get().autoMineAttackMobs)
            .setTooltip(Component.literal("开启后：自动挖矿时自动攻击 3.5 格内的敌对生物（僵尸、苦力怕等），避免挖矿被打断。关闭后挖矿期间不再自动攻击怪物（可用 /os f 飞行躲避近战）。"))
            .setSaveConsumer(val -> {
               Config.get().autoMineAttackMobs = val;
               Config.save();
            })
            .build()
      );
      String seedText = m.isActive() ? String.valueOf(m.getWorldSeed()) : "-";
      ConfigCategory seeds = builder.getOrCreateCategory(Component.literal("种子记录"));
      seeds.addEntry(
         eb.startTextDescription(Component.literal("当前种子: " + seedText + (Config.get().activeVersion.isEmpty() ? "" : "  版本: " + Config.get().activeVersion)))
            .build()
      );
      if (Config.get().seedHistory.isEmpty()) {
         seeds.addEntry(eb.startTextDescription(Component.literal("暂无种子记录，使用 /os sz <种子> [版本] 添加").withStyle(ChatFormatting.GRAY)).build());
      } else {
         List<Config.SeedEntry> history = Config.get().seedHistory;
         seeds.addEntry(eb.startTextDescription(Component.literal("已保存种子记录（共 " + history.size() + " 条，最多 20 条）：")).build());

         for (int i = 0; i < history.size(); i++) {
            Config.SeedEntry e = history.get(i);
            boolean isActive = m.isActive() && m.getWorldSeed() == e.seed;
            String prefix = isActive ? "§a[当前] " : "§7";
            String ver = e.version != null && !e.version.isEmpty() ? "  版本:" + e.version : "";
            seeds.addEntry(eb.startTextDescription(Component.literal(prefix + (i + 1) + ". 种子 " + e.seed + ver)).build());
         }

         List<Integer> options = new ArrayList<>();
         options.add(-1);

         for (int i = 0; i < history.size(); i++) {
            options.add(i);
         }

         Integer[] opts = options.toArray(new Integer[0]);
         seeds.addEntry(eb.startSelector(Component.literal("切换到"), opts, -1).setNameProvider(idx -> {
            if (idx == null || idx == -1) {
               return Component.literal("不切换");
            } else if (idx >= 0 && idx < history.size()) {
               Config.SeedEntry e = history.get(idx);
               return Component.literal("种子 " + e.seed + (e.version.isEmpty() ? "" : " (" + e.version + ")"));
            } else {
               return Component.literal("不切换");
            }
         }).setSaveConsumer(idx -> {
            if (idx != null) {
               switchToIdx[0] = idx;
            }
         }).build());
         seeds.addEntry(eb.startBooleanToggle(Component.literal("删除选中的种子记录"), false).setSaveConsumer(del -> deleteSelected[0] = del).build());
      }

      settings.addEntry(eb.startTextDescription(Component.literal("当前种子: " + seedText)).build());
      return builder.build();
   }
}
