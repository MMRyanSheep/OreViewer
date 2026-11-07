package kaptainwutax.seedcrackerX.automine;

import kaptainwutax.seedcrackerX.config.Config;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class AutoMineScreen {
   public static Screen create(Screen parent) {
      Config config = Config.get();
      AutoMineManager m = AutoMineManager.INSTANCE;
      ConfigBuilder builder = ConfigBuilder.create()
         .setParentScreen(parent)
         .setTitle(Component.translatable("zd.title").withStyle(ChatFormatting.GOLD))
         .setDefaultBackgroundTexture(Identifier.parse("minecraft:textures/block/deepslate_diamond_ore.png"))
         .setTransparentBackground(true)
         .setSavingRunnable(Config::save);
      ConfigEntryBuilder eb = builder.entryBuilder();
      ConfigCategory settings = builder.getOrCreateCategory(Component.translatable("zd.settings"));
      settings.addEntry(
         eb.startBooleanToggle(Component.translatable("zd.autoReturn"), config.autoMineAutoReturn)
            .setDefaultValue(true)
            .setSaveConsumer(val -> config.autoMineAutoReturn = val)
            .build()
      );
      settings.addEntry(
         eb.startIntSlider(Component.translatable("zd.returnDelay"), config.autoMineReturnDelay, 0, 30)
            .setDefaultValue(5)
            .setSaveConsumer(val -> config.autoMineReturnDelay = val)
            .build()
      );
      settings.addEntry(eb.startTextDescription(Component.translatable("zd.baritoneNote")).build());

      settings.addEntry(eb.startTextDescription(Component.literal(Component.translatable("zd.statusLabel").getString() + switch (m.getFirstTask()) {
         case DIAMOND -> Component.translatable("zd.statusDiamond").getString();
         case DEBRIS -> Component.translatable("zd.statusDebris").getString();
         case IRON -> Component.translatable("zd.statusIron").getString();
         case GOLD -> Component.translatable("zd.statusGold").getString();
         case COAL -> Component.translatable("zd.statusCoal").getString();
         case REDSTONE -> Component.translatable("zd.statusRedstone").getString();
         case LAPIS -> Component.translatable("zd.statusLapis").getString();
         case COPPER -> Component.translatable("zd.statusCopper").getString();
         case EMERALD -> Component.translatable("zd.statusEmerald").getString();
         default -> Component.translatable("zd.statusOff").getString();
      })).build());
      settings.addEntry(eb.startTextDescription(Component.translatable("zd.usage")).build());
      return builder.build();
   }
}
