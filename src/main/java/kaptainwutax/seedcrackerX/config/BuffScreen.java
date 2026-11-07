package kaptainwutax.seedcrackerX.config;

import kaptainwutax.seedcrackerX.buff.BuffManager;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class BuffScreen {
   public static Screen create(Screen parent) {
      BuffManager bm = BuffManager.INSTANCE;
      ConfigBuilder builder = ConfigBuilder.create()
         .setParentScreen(parent)
         .setTitle(Component.literal("BUFF 设置").withStyle(ChatFormatting.GOLD))
         .setDefaultBackgroundTexture(Identifier.parse("minecraft:textures/block/amethyst_block.png"))
         .setTransparentBackground(true)
         .setSavingRunnable(() -> bm.saveToConfig());
      ConfigEntryBuilder eb = builder.entryBuilder();
      ConfigCategory cat = builder.getOrCreateCategory(Component.literal("✨ BUFF 效果"));
      cat.addEntry(eb.startTextDescription(Component.literal("已开启的 BUFF 会自动刷新（持续 20 秒）。等级 0 = I 级，4 = V 级。").withStyle(ChatFormatting.GRAY)).build());
      cat.addEntry(eb.startTextDescription(Component.literal("")).build());

      for (BuffManager.BuffType t : BuffManager.BuffType.values()) {
         cat.addEntry(
            eb.startBooleanToggle(Component.literal(t.name).withStyle(ChatFormatting.YELLOW), bm.isEnabled(t))
               .setSaveConsumer(val -> bm.setEnabled(t, val))
               .build()
         );
         cat.addEntry(eb.startIntSlider(Component.literal("  └ " + t.name + " 等级"), bm.getLevel(t), 0, 4).setSaveConsumer(val -> bm.setLevel(t, val)).build());
      }

      return builder.build();
   }
}
