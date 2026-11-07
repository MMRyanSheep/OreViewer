package kaptainwutax.seedcrackerX.buff;

import java.util.LinkedHashMap;
import java.util.Map;
import kaptainwutax.seedcrackerX.config.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

public class BuffManager {
   public static final BuffManager INSTANCE = new BuffManager();
   private final Map<BuffManager.BuffType, Boolean> enabled = new LinkedHashMap<>();
   private final Map<BuffManager.BuffType, Integer> levels = new LinkedHashMap<>();
   private int tickCounter = 0;

   private BuffManager() {
      for (BuffManager.BuffType t : BuffManager.BuffType.values()) {
         this.enabled.put(t, t.defaultEnabled);
         this.levels.put(t, t.defaultLevel);
      }
   }

   public void loadFromConfig() {
      Config c = Config.get();

      for (BuffManager.BuffType t : BuffManager.BuffType.values()) {
         this.enabled.put(t, c.buffEnabled(t));
         this.levels.put(t, c.buffLevel(t));
      }
   }

   public void saveToConfig() {
      Config c = Config.get();

      for (BuffManager.BuffType t : BuffManager.BuffType.values()) {
         c.setBuff(t, this.enabled.get(t), this.levels.get(t));
      }

      Config.save();
   }

   public boolean isEnabled(BuffManager.BuffType t) {
      return this.enabled.getOrDefault(t, false);
   }

   public void setEnabled(BuffManager.BuffType t, boolean v) {
      this.enabled.put(t, v);
   }

   public int getLevel(BuffManager.BuffType t) {
      return this.levels.getOrDefault(t, 0);
   }

   public void setLevel(BuffManager.BuffType t, int lvl) {
      this.levels.put(t, Math.max(0, Math.min(4, lvl)));
   }

   public void tick() {
      if (--this.tickCounter <= 0) {
         this.tickCounter = 200;
         Minecraft mc = Minecraft.getInstance();
         if (mc != null && mc.player != null) {
            for (BuffManager.BuffType t : BuffManager.BuffType.values()) {
               if (this.isEnabled(t)) {
                  mc.player.addEffect(new MobEffectInstance(t.effect, 400, this.getLevel(t), false, false, false));
               }
            }
         }
      }
   }

   public enum BuffType {
      NIGHT_VISION(MobEffects.NIGHT_VISION, "夜视", 0, false),
      DIG_SPEED(MobEffects.HASTE, "急迫", 1, false),
      MOVEMENT_SPEED(MobEffects.SPEED, "急速", 1, false),
      DAMAGE_BOOST(MobEffects.STRENGTH, "力量", 1, false),
      DAMAGE_RESISTANCE(MobEffects.RESISTANCE, "抗性", 1, false),
      FIRE_RESISTANCE(MobEffects.FIRE_RESISTANCE, "防火", 0, false),
      WATER_BREATHING(MobEffects.WATER_BREATHING, "水下呼吸", 0, false),
      JUMP(MobEffects.JUMP_BOOST, "跳跃", 1, false),
      REGENERATION(MobEffects.REGENERATION, "生命恢复", 1, false),
      SATURATION(MobEffects.SATURATION, "饱和", 0, false);

      public final Holder<MobEffect> effect;
      public final String name;
      public final int defaultLevel;
      public final boolean defaultEnabled;

      BuffType(Holder<MobEffect> effect, String name, int defaultLevel, boolean defaultEnabled) {
         this.effect = effect;
         this.name = name;
         this.defaultLevel = defaultLevel;
         this.defaultEnabled = defaultEnabled;
      }
   }
}
