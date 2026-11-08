package kaptainwutax.seedcrackerX.config;

import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class HelpScreen {
   public static Screen getHelpScreen(Screen parent) {
      ConfigBuilder builder = ConfigBuilder.create()
         .setParentScreen(parent)
         .setTitle(Component.literal("SeedCrackerX 使用帮助"))
         .setDefaultBackgroundTexture(Identifier.parse("minecraft:textures/block/blackstone.png"))
         .setTransparentBackground(true);
      ConfigEntryBuilder eb = builder.entryBuilder();
      ConfigCategory seed = builder.getOrCreateCategory(Component.literal("\ud83c\udf31 种子设置"));
      seed.addEntry(eb.startTextDescription(Component.literal("").withStyle(s -> s.withColor(ChatFormatting.AQUA))).build());
      seed.addEntry(eb.startTextDescription(Component.literal("设置世界种子，用于矿物预测和结构指引。")).build());
      seed.addEntry(eb.startTextDescription(Component.literal("")).build());
      seed.addEntry(eb.startTextDescription(Component.literal("/os sz <种子> [版本]").withStyle(s -> s.withColor(ChatFormatting.YELLOW))).build());
      seed.addEntry(eb.startTextDescription(Component.literal("  设置种子，可选版本号（如 1.21.3、1.20.1、1.18.2）。")).build());
      seed.addEntry(eb.startTextDescription(Component.literal("  支持全版本，输入哪个版本就用哪个版本的算法渲染。")).build());
      seed.addEntry(eb.startTextDescription(Component.literal("  示例：/os sz 1234567890 1.21.3").withStyle(s -> s.withColor(ChatFormatting.GRAY))).build());
      seed.addEntry(eb.startTextDescription(Component.literal("")).build());
      seed.addEntry(eb.startTextDescription(Component.literal("种子会自动保存到本地配置，重启游戏后自动恢复。").withStyle(s -> s.withColor(ChatFormatting.GRAY))).build());
      ConfigCategory automine = builder.getOrCreateCategory(Component.literal("⛏ 自动挖矿"));
      automine.addEntry(eb.startTextDescription(Component.literal("基于种子预测矿物位置，使用 Baritone 自动寻路挖矿。")).build());
      automine.addEntry(eb.startTextDescription(Component.literal("")).build());
      automine.addEntry(eb.startTextDescription(Component.literal("/os zd <矿物>").withStyle(s -> s.withColor(ChatFormatting.YELLOW))).build());
      automine.addEntry(eb.startTextDescription(Component.literal("  开始自动挖矿。支持的矿物：")).build());
      automine.addEntry(
         eb.startTextDescription(Component.literal("  钻石 / 红石 / 绿宝石 / 下界合金 / 下界石英 / 铁 / 金 / 青金石 / 煤矿").withStyle(s -> s.withColor(ChatFormatting.WHITE)))
            .build()
      );
      automine.addEntry(eb.startTextDescription(Component.literal("  示例：/os zd 钻石").withStyle(s -> s.withColor(ChatFormatting.GRAY))).build());
      automine.addEntry(eb.startTextDescription(Component.literal("")).build());
      automine.addEntry(eb.startTextDescription(Component.literal("/os zd 停止").withStyle(s -> s.withColor(ChatFormatting.YELLOW))).build());
      automine.addEntry(eb.startTextDescription(Component.literal("  停止自动挖矿。")).build());
      automine.addEntry(eb.startTextDescription(Component.literal("")).build());
      automine.addEntry(eb.startTextDescription(Component.literal("/os ch [开|关|状态]").withStyle(s -> s.withColor(ChatFormatting.YELLOW))).build());
      automine.addEntry(
         eb.startTextDescription(
            Component.literal("  背包满自动存盒开关。背包全满且背包内有未满的潜影盒时，自动把矿物等物品存入盒子并取回，存完继续挖矿。")
               .withStyle(s -> s.withColor(ChatFormatting.WHITE))
         ).build()
      );
      automine.addEntry(eb.startTextDescription(Component.literal("")).build());
      automine.addEntry(eb.startTextDescription(Component.literal("注意事项：").withStyle(s -> s.withColor(ChatFormatting.RED))).build());
      automine.addEntry(eb.startTextDescription(Component.literal("  - 必须先设置种子（/os sz）").withStyle(s -> s.withColor(ChatFormatting.WHITE))).build());
      automine.addEntry(eb.startTextDescription(Component.literal("  - 必须安装 Baritone").withStyle(s -> s.withColor(ChatFormatting.WHITE))).build());
      automine.addEntry(eb.startTextDescription(Component.literal("  - 打开 GUI（背包/聊天）会暂停挖矿").withStyle(s -> s.withColor(ChatFormatting.WHITE))).build());
      automine.addEntry(
         eb.startTextDescription(Component.literal("  - 包满自动存盒默认开启，可用 /os ch 开关；需提前在背包里放好潜影盒，附近要有空地").withStyle(s -> s.withColor(ChatFormatting.WHITE))).build()
      );
      ConfigCategory eat = builder.getOrCreateCategory(Component.literal("自动进食"));
      eat.addEntry(eb.startTextDescription(Component.literal("全局功能：饱食度不足时自动食用背包内食物，无需开启自动挖矿。")).build());
      eat.addEntry(eb.startTextDescription(Component.literal("")).build());
      eat.addEntry(eb.startTextDescription(Component.literal("/os eat [开|关|状态]").withStyle(s -> s.withColor(ChatFormatting.YELLOW))).build());
      eat.addEntry(eb.startTextDescription(Component.literal("  开关自动进食 / 查看当前状态。默认开启。")).build());
      eat.addEntry(eb.startTextDescription(Component.literal("")).build());
      eat.addEntry(eb.startTextDescription(Component.literal("/os eat 阈值 <1-19>").withStyle(s -> s.withColor(ChatFormatting.YELLOW))).build());
      eat.addEntry(eb.startTextDescription(Component.literal("  设置触发饱食度（默认 18）。饱食度 ≤ 该值时自动进食。")).build());
      eat.addEntry(eb.startTextDescription(Component.literal("")).build());
      eat.addEntry(eb.startTextDescription(Component.literal("说明：").withStyle(s -> s.withColor(ChatFormatting.AQUA))).build());
      eat.addEntry(eb.startTextDescription(Component.literal("  - 会优先选刚好补满、浪费最少的食物；背包 27 格里的食物会临时换到快捷栏，吃完还原")).build());
      eat.addEntry(eb.startTextDescription(Component.literal("  - 蜘蛛眼/河豚/毒马铃薯/紫颂果/谜之炖菜永不自动食用；金苹果、腐肉等仅在快饿死时考虑")).build());
      eat.addEntry(eb.startTextDescription(Component.literal("  - 打开界面、手动右键/挖掘、自动存盒/自动挖矿/鞘翅收集期间会暂停")).build());
      eat.addEntry(eb.startTextDescription(Component.literal("  - 矿物透视设置界面里也能开关和调整阈值")).build());
      ConfigCategory xray = builder.getOrCreateCategory(Component.literal("\ud83d\udc8e 矿物透视"));
      xray.addEntry(eb.startTextDescription(Component.literal("通过种子预测矿物位置并高亮显示。")).build());
      xray.addEntry(eb.startTextDescription(Component.literal("")).build());
      xray.addEntry(eb.startTextDescription(Component.literal("/os xs").withStyle(s -> s.withColor(ChatFormatting.YELLOW))).build());
      xray.addEntry(eb.startTextDescription(Component.literal("  打开矿物透视设置界面。")).build());
      xray.addEntry(
         eb.startTextDescription(Component.literal("  支持：钻石、铁、金、青金石、红石、煤矿、下界合金、下界石英、绿宝石、铜、锡、铅").withStyle(s -> s.withColor(ChatFormatting.WHITE))).build()
      );
      xray.addEntry(eb.startTextDescription(Component.literal("")).build());
      xray.addEntry(eb.startTextDescription(Component.literal("功能说明：").withStyle(s -> s.withColor(ChatFormatting.AQUA))).build());
      xray.addEntry(eb.startTextDescription(Component.literal("  - 矿物透视开关状态会保存，死亡复活不会重置").withStyle(s -> s.withColor(ChatFormatting.WHITE))).build());
      xray.addEntry(eb.startTextDescription(Component.literal("  - 包满自动存盒开关（也可用 /os ch 快速开关）").withStyle(s -> s.withColor(ChatFormatting.WHITE))).build());
      ConfigCategory dx = builder.getOrCreateCategory(Component.literal("\ud83c\udfdb 地形指引"));
      dx.addEntry(eb.startTextDescription(Component.literal("通过种子计算结构位置，用射线指引前往。")).build());
      dx.addEntry(eb.startTextDescription(Component.literal("")).build());
      dx.addEntry(eb.startTextDescription(Component.literal("/os dx <结构>").withStyle(s -> s.withColor(ChatFormatting.YELLOW))).build());
      dx.addEntry(eb.startTextDescription(Component.literal("  开始结构指引。支持的结构：")).build());
      dx.addEntry(
         eb.startTextDescription(
               Component.literal("  末地城 / 海底神殿 / 沙漠神殿 / 丛林神殿 / 沉船 / 沼泽小屋 / 掠夺者前哨站 / 雪屋 / 试练厅 / 古城遗址 / 地狱堡垒 / 猪灵堡垒")
                  .withStyle(s -> s.withColor(ChatFormatting.WHITE))
            )
            .build()
      );
      dx.addEntry(eb.startTextDescription(Component.literal("  示例：/os dx 古城遗址").withStyle(s -> s.withColor(ChatFormatting.GRAY))).build());
      dx.addEntry(eb.startTextDescription(Component.literal("")).build());
      dx.addEntry(eb.startTextDescription(Component.literal("/os dx 停止").withStyle(s -> s.withColor(ChatFormatting.YELLOW))).build());
      dx.addEntry(eb.startTextDescription(Component.literal("  停止结构指引。")).build());
      ConfigCategory mdc = builder.getOrCreateCategory(Component.literal("\ud83c\udf32 生物群系指引"));
      mdc.addEntry(eb.startTextDescription(Component.literal("通过种子计算生物群系位置，用射线指引前往。")).build());
      mdc.addEntry(eb.startTextDescription(Component.literal("")).build());
      mdc.addEntry(eb.startTextDescription(Component.literal("/os mdc <生物群系>").withStyle(s -> s.withColor(ChatFormatting.YELLOW))).build());
      mdc.addEntry(eb.startTextDescription(Component.literal("  开始生物群系指引。支持的生物群系：")).build());
      mdc.addEntry(
         eb.startTextDescription(
               Component.literal("  繁茂洞穴 / 紫水晶洞 / 冻洋 / 深暗之域 / 樱花林 / 恶地 / 竹林丛林 / 黑森林 / 深海 / 蘑菇岛 / 冰刺平原 / 丛林 / 暖水海洋 / 向日葵草原")
                  .withStyle(s -> s.withColor(ChatFormatting.WHITE))
            )
            .build()
      );
      mdc.addEntry(eb.startTextDescription(Component.literal("  示例：/os mdc 繁茂洞穴").withStyle(s -> s.withColor(ChatFormatting.GRAY))).build());
      mdc.addEntry(eb.startTextDescription(Component.literal("")).build());
      mdc.addEntry(eb.startTextDescription(Component.literal("/os mdc 停止").withStyle(s -> s.withColor(ChatFormatting.YELLOW))).build());
      mdc.addEntry(eb.startTextDescription(Component.literal("  停止生物群系指引。")).build());
      ConfigCategory fk = builder.getOrCreateCategory(Component.literal("\ud83c\udfaf 方块指引"));
      fk.addEntry(eb.startTextDescription(Component.literal("通过种子计算指定方块位置，指引最近的3个方块。")).build());
      fk.addEntry(eb.startTextDescription(Component.literal("")).build());
      fk.addEntry(eb.startTextDescription(Component.literal("/os fk <方块ID>").withStyle(s -> s.withColor(ChatFormatting.YELLOW))).build());
      fk.addEntry(eb.startTextDescription(Component.literal("  开始方块指引，指引最近的3个该方块。")).build());
      fk.addEntry(eb.startTextDescription(Component.literal("  示例：/os fk diamond_ore").withStyle(s -> s.withColor(ChatFormatting.GRAY))).build());
      fk.addEntry(eb.startTextDescription(Component.literal("  示例：/os fk ancient_debris").withStyle(s -> s.withColor(ChatFormatting.GRAY))).build());
      fk.addEntry(eb.startTextDescription(Component.literal("")).build());
      fk.addEntry(eb.startTextDescription(Component.literal("/os fk off").withStyle(s -> s.withColor(ChatFormatting.YELLOW))).build());
      fk.addEntry(eb.startTextDescription(Component.literal("  关闭方块指引。")).build());
      ConfigCategory other = builder.getOrCreateCategory(Component.literal("\ud83d\udccb 其他功能"));
      other.addEntry(eb.startTextDescription(Component.literal("/os buff").withStyle(s -> s.withColor(ChatFormatting.YELLOW))).build());
      other.addEntry(eb.startTextDescription(Component.literal("  打开 BUFF 设置界面。")).build());
      other.addEntry(
         eb.startTextDescription(Component.literal("  支持：夜视、急迫、急速、力量、抗性、防火、水下呼吸、跳跃、生命恢复、饱和").withStyle(s -> s.withColor(ChatFormatting.WHITE))).build()
      );
      other.addEntry(eb.startTextDescription(Component.literal("  每个 BUFF 可单独开关并设置等级（I-V 级）。").withStyle(s -> s.withColor(ChatFormatting.WHITE))).build());
      other.addEntry(
         eb.startTextDescription(Component.literal("  所有 BUFF 默认关闭，可在界面中按需开启。").withStyle(s -> s.withColor(ChatFormatting.GRAY))).build()
      );
      other.addEntry(eb.startTextDescription(Component.literal("")).build());
      other.addEntry(eb.startTextDescription(Component.literal("/os gui").withStyle(s -> s.withColor(ChatFormatting.YELLOW))).build());
      other.addEntry(eb.startTextDescription(Component.literal("  打开 Finder 设置界面。")).build());
      other.addEntry(eb.startTextDescription(Component.literal("")).build());
      other.addEntry(eb.startTextDescription(Component.literal("/os render <类型>").withStyle(s -> s.withColor(ChatFormatting.YELLOW))).build());
      other.addEntry(eb.startTextDescription(Component.literal("  设置渲染类型（off / cube / outline）。")).build());
      other.addEntry(eb.startTextDescription(Component.literal("")).build());
      other.addEntry(eb.startTextDescription(Component.literal("/os finder <类型>").withStyle(s -> s.withColor(ChatFormatting.YELLOW))).build());
      other.addEntry(eb.startTextDescription(Component.literal("  启用/禁用 Finder。")).build());
      other.addEntry(eb.startTextDescription(Component.literal("")).build());
      other.addEntry(eb.startTextDescription(Component.literal("/os database").withStyle(s -> s.withColor(ChatFormatting.YELLOW))).build());
      other.addEntry(eb.startTextDescription(Component.literal("  打开数据库设置。")).build());
      other.addEntry(eb.startTextDescription(Component.literal("")).build());
      other.addEntry(eb.startTextDescription(Component.literal("/os version").withStyle(s -> s.withColor(ChatFormatting.YELLOW))).build());
      other.addEntry(eb.startTextDescription(Component.literal("  查看 Mod 版本。")).build());
      other.addEntry(eb.startTextDescription(Component.literal("")).build());
      other.addEntry(eb.startTextDescription(Component.literal("/os kickstart").withStyle(s -> s.withColor(ChatFormatting.YELLOW))).build());
      other.addEntry(eb.startTextDescription(Component.literal("  重新扫描已加载区块。")).build());
      builder.setSavingRunnable(() -> {});
      return builder.build();
   }
}
