package kaptainwutax.seedcrackerX.automine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import kaptainwutax.seedcrackerX.autoeat.AutoEatManager;
import kaptainwutax.seedcrackerX.command.ClientCommand;
import kaptainwutax.seedcrackerX.config.Config;
import kaptainwutax.seedcrackerX.util.Compat;
import kaptainwutax.seedcrackerX.xray.OreXrayManager;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket.Action;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ClipContext.Fluid;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.HitResult.Type;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoMineManager {
   public static final AutoMineManager INSTANCE = new AutoMineManager();
   private static final Logger LOGGER = LoggerFactory.getLogger("SeedCrackerX-AutoMine");
   public static volatile boolean proMode = false;
   private static final int REISSUE_INTERVAL_TICKS = 100;
   private static final int REPAIR_AT_DURABILITY = 20;
   private static final float REPAIR_RESUME_FRACTION = 0.8F;
   private static final int REPAIR_MAX_TICKS = 600;
   private static final int SWAP_AT_DURABILITY = 30;
   private static final int SWAP_COOLDOWN_TICKS = 10;
   private static final double ATTACK_RANGE = 3.5;
   private static final float ATTACK_READY = 0.9F;
   private static final int ROAM_DISTANCE = 48;
   private static final double ROAM_ARRIVE = 8.0;
   private static final int MAX_INJECTED_TARGETS = 16;
   private static final double LOOT_PICKUP_RANGE = 4.0;
   private static final int LOOT_SCAN_TICKS = 10;
   private static final int VEIN_SETTLE_TICKS = 20;
   private static final int STUCK_WATCHDOG_TICKS = 100;
   private static final double STUCK_MOVE_THRESHOLD = 1.0;
   private static final int STUCK_ABORT_TICKS = 200;
   private final Set<AutoMineManager.Task> task = new LinkedHashSet<>();
   private AutoMineManager.Phase phase = AutoMineManager.Phase.WORK;
   private boolean backPending;
   private int backDelayTicks;
   private int freezeTicks;
   private int eatTicks;
   private int eatSwapMenuSlot = -1;
   private int reissueCooldown;
   private int swapCooldown;
   private Set<Long> lastInjected = new HashSet<>();
   private BlockPos lastInjectPos;
   private BlockPos roamGoal;
   private double roamAngle;
   private int statusTicks;
   private Set<AutoMineManager.Task> taskBeforeRepair = new LinkedHashSet<>();
   private int repairTicks;
   private int veinSettleTicks;
   private boolean veinSettleLatched;
   private int lootScanTicks;
   private final Set<Long> seedUnreachable = new HashSet<>();
   private BlockPos seedTarget;
   private boolean seedMining;
   private int seedTargetTicks;
   private int seedMineTicks;
   private int seedSettleTicks;
   private BlockPos seedCollectPos = null;
   private int seedCollectRepathTicks = 0;
   private int seedRescanTicks = 0;
   private int seedStuckTicks = 0;
   private long seedVeinStartMs = 0L;
   private static final double VERIFY_REVEAL_RANGE_SQ = 36.0;
   private final Set<Long> seedDonePos = new HashSet<>();
   private Object seedTaskCache = null;
   private int seedRefreshTicks = 0;
   private int seedIdleTicks = 0;
   private int seedEscapeTicks = 0;
   private List<BlockPos> seedVein = null;
   private boolean seedVeinActive = false;
   private long lastInfoMs;
   private BlockPos lastStuckCheckPos;
   private int stuckTicks;

   public static void setProMode(boolean on) {
      proMode = on;
   }

   private AutoMineManager() {
   }

   public void start(Set<AutoMineManager.Task> newTasks) {
      if (!FabricLoader.getInstance().isModLoaded("baritone")) {
         this.info("未检测到 Baritone。请将 Baritone 的 jar 与本 mod 一同放入 mods 文件夹后再使用自动挖矿。");
      } else if (newTasks == null || newTasks.isEmpty()) {
         this.info("请指定要挖掘的矿物类型，例如 /ov zd 钻石 青金石。");
      } else if (!OreXrayManager.INSTANCE.isActive()) {
         this.info("请先用 /ov sz <种子> 设置种子，自动挖矿根据种子预测的矿物位置进行挖掘。pro 破译出种子后点击 [直接设置] 即可。");
      } else {
         this.task.clear();
         this.task.addAll(newTasks);
         this.taskBeforeRepair.clear();
         this.phase = AutoMineManager.Phase.WORK;
         this.backPending = false;
         this.freezeTicks = 0;
         this.eatTicks = 0;
         this.eatSwapMenuSlot = -1;
         this.reissueCooldown = 0;
         this.repairTicks = 0;
         this.swapCooldown = 0;
         this.lastInjected = new HashSet<>();
         this.lastInjectPos = null;
         this.roamGoal = null;
         this.roamAngle = 0.0;
         this.statusTicks = 0;
         this.veinSettleTicks = 0;
         this.veinSettleLatched = false;
         this.lootScanTicks = 0;
         this.seedUnreachable.clear();
         this.seedDonePos.clear();
         this.seedTarget = null;
         this.seedMining = false;
         this.seedTargetTicks = 0;
         this.seedMineTicks = 0;
         this.seedSettleTicks = 0;
         this.enableXrayForTasks(newTasks);

         try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gameRenderer != null) {
               Camera cam = Compat.mainCamera(mc.gameRenderer);
               if (cam != null) {
                  OreXrayManager.INSTANCE.getCuboids(cam);
               }
            }
         } catch (Throwable var4) {
         }

         this.info(
            "自动挖矿已启动（就近挖掘种子矿物，严格限制在 /ov xs 渲染范围 "
               + Math.max(1, OreXrayManager.INSTANCE.renderDistance)
               + " 个区块半径内，由 Baritone 原生寻路挖掘并自动绕岩浆/水）："
               + this.taskName(newTasks)
               + "。停止请输入 /ov zd 停止。"
         );
      }
   }

   private void enableXrayForTasks(Set<AutoMineManager.Task> tasks) {
      OreXrayManager xm = OreXrayManager.INSTANCE;
      boolean changed = false;

      for (AutoMineManager.Task t : tasks) {
         switch (t) {
            case DIAMOND:
               if (!xm.diamond.get()) {
                  xm.diamond.set(true);
                  changed = true;
               }
               break;
            case DEBRIS:
               if (!xm.ancientDebris.get()) {
                  xm.ancientDebris.set(true);
                  changed = true;
               }
               break;
            case IRON:
               if (!xm.iron.get()) {
                  xm.iron.set(true);
                  changed = true;
               }
               break;
            case GOLD:
               if (!xm.gold.get()) {
                  xm.gold.set(true);
                  changed = true;
               }
               break;
            case COAL:
               if (!xm.coal.get()) {
                  xm.coal.set(true);
                  changed = true;
               }
               break;
            case REDSTONE:
               if (!xm.redstone.get()) {
                  xm.redstone.set(true);
                  changed = true;
               }
               break;
            case LAPIS:
               if (!xm.lapis.get()) {
                  xm.lapis.set(true);
                  changed = true;
               }
               break;
            case COPPER:
               if (!xm.copper.get()) {
                  xm.copper.set(true);
                  changed = true;
               }
               break;
            case EMERALD:
               if (!xm.emerald.get()) {
                  xm.emerald.set(true);
                  changed = true;
               }
         }
      }

      if (changed) {
         try {
            xm.saveSettings();
         } catch (Throwable var6) {
         }
      }
   }

   public void stop() {
      if (!this.task.isEmpty()) {
         try {
            if (FabricLoader.getInstance().isModLoaded("baritone")) {
               BaritoneBridge.cancel();
            }
         } catch (Throwable t) {
            LOGGER.error("[automine] failed to stop Baritone", t);
         }
      }

      this.task.clear();
      this.phase = AutoMineManager.Phase.WORK;
      this.backPending = false;
      this.lastInjected = new HashSet<>();
      this.lastInjectPos = null;
      this.roamGoal = null;
      this.seedTarget = null;
      this.seedMining = false;
      this.seedTargetTicks = 0;
      this.seedMineTicks = 0;
      this.seedSettleTicks = 0;
      this.veinSettleTicks = 0;
      this.veinSettleLatched = false;
      this.releaseInputs();
      this.info("自动挖矿已停止。");
   }

   public boolean isActive() {
      return !this.task.isEmpty();
   }

   public Set<AutoMineManager.Task> getTasks() {
      return this.task;
   }

   public AutoMineManager.Task getFirstTask() {
      return this.task.isEmpty() ? AutoMineManager.Task.OFF : this.task.iterator().next();
   }

   private Block[] blocksFor(AutoMineManager.Task t) {
      switch (t) {
         case DIAMOND:
         default:
            return new Block[]{Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE};
         case DEBRIS:
            return new Block[]{Blocks.ANCIENT_DEBRIS};
         case IRON:
            return new Block[]{Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE, Blocks.RAW_IRON_BLOCK};
         case GOLD:
            return new Block[]{Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.RAW_GOLD_BLOCK};
         case COAL:
            return new Block[]{Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE};
         case REDSTONE:
            return new Block[]{Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE};
         case LAPIS:
            return new Block[]{Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE};
         case COPPER:
            return new Block[]{Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE};
         case EMERALD:
            return new Block[]{Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE};
      }
   }

   private Block[] blocksFor(Set<AutoMineManager.Task> tasks) {
      List<Block> all = new ArrayList<>();

      for (AutoMineManager.Task t : tasks) {
         for (Block b : this.blocksFor(t)) {
            if (!all.contains(b)) {
               all.add(b);
            }
         }
      }

      return all.toArray(new Block[0]);
   }

   private String taskName(AutoMineManager.Task t) {
      switch (t) {
         case DIAMOND:
         default:
            return "钻石矿 / 深层钻石矿";
         case DEBRIS:
            return "远古残骸";
         case IRON:
            return "铁矿 / 深层铁矿";
         case GOLD:
            return "金矿 / 深层金矿";
         case COAL:
            return "煤矿 / 深层煤矿";
         case REDSTONE:
            return "红石矿 / 深层红石矿";
         case LAPIS:
            return "青金石矿 / 深层青金石矿";
         case COPPER:
            return "铜矿 / 深层铜矿";
         case EMERALD:
            return "绿宝石矿 / 深层绿宝石矿";
      }
   }

   private String taskName(Set<AutoMineManager.Task> tasks) {
      if (tasks != null && !tasks.isEmpty()) {
         List<String> names = new ArrayList<>();

         for (AutoMineManager.Task t : tasks) {
            names.add(this.taskName(t));
         }

         return String.join(" + ", names);
      } else {
         return "无";
      }
   }

   public void onClientTick() {
      if (!this.task.isEmpty()) {
         Minecraft mc = Minecraft.getInstance();
         if (mc != null && mc.level != null && mc.player != null && mc.gameMode != null) {
            LocalPlayer player = mc.player;
            Level level = mc.level;

            try {
               // 全局自动进食正在占用右键/快捷栏时让路（例如进食途中开启了自动挖矿）。
               if (AutoEatManager.INSTANCE.isBusy()) {
                  return;
               }

               if (Compat.screen(mc) instanceof DeathScreen) {
                  this.handleDeath(mc);
                  return;
               }

               if (this.backPending) {
                  this.releaseInputs();
                  if (--this.backDelayTicks <= 0 && mc.getConnection() != null) {
                     mc.getConnection().sendCommand("back");
                     this.backPending = false;
                     this.freezeTicks = Math.max(0, Config.get().autoMineReturnDelay) * 20;
                     this.phase = AutoMineManager.Phase.FREEZE;
                     this.info("已发送 /back，原地等待 " + Config.get().autoMineReturnDelay + " 秒后继续。");
                  }

                  return;
               }

               if (this.phase == AutoMineManager.Phase.FREEZE) {
                  this.releaseInputs();
                  if (--this.freezeTicks <= 0) {
                     this.phase = AutoMineManager.Phase.WORK;
                     this.resumeMining();
                  }

                  return;
               }

               // 背包满自动存盒由 ShulkerAutoStore 统一驱动，存盒期间自动挖矿让路。
               if (ShulkerAutoStore.INSTANCE.isBusy()) {
                  return;
               }

               if (Compat.screen(mc) != null) {
                  return;
               }

               if (this.phase != AutoMineManager.Phase.EAT && player.getFoodData().getFoodLevel() <= AutoEatManager.triggerLevel()) {
                  this.phase = AutoMineManager.Phase.EAT;
                  this.eatTicks = 0;
                  this.eatSwapMenuSlot = -1;
                  this.safeCancel();
               }

               if (this.phase == AutoMineManager.Phase.EAT) {
                  this.tickEat(mc, player);
                  return;
               }

               if (Config.get().autoMineAttackMobs) {
                  this.tickCombat(mc, player, level);
               }

               int pickSlot = this.findPickaxeSlot(player);
               if (pickSlot < 0) {
                  this.halt("快捷栏内未找到镐子，自动挖矿已停止。");
                  return;
               }

               int bestPick = this.bestHotbarPickaxeSlot(player);
               int bestRem = bestPick < 0 ? 0 : this.remainingDurability(player.getInventory().getItem(bestPick));
               if (this.phase == AutoMineManager.Phase.WORK && bestPick >= 0 && bestRem <= 30 && --this.swapCooldown <= 0) {
                  int spareMenu = this.findSparePickaxeMenuSlot(player);
                  if (spareMenu >= 0) {
                     int emptyHot = this.findEmptyHotbarSlot(player);
                     int targetHot = emptyHot >= 0 ? emptyHot : bestPick;
                     mc.gameMode.handleContainerInput(0, spareMenu, targetHot, ContainerInput.SWAP, player);
                     player.getInventory().setSelectedSlot(targetHot);
                     this.swapCooldown = 10;
                     return;
                  }

                  if (!this.repairPossible(player, bestPick, level)) {
                     this.halt("镐子耐久不足（剩余 " + bestRem + "）且背包中没有耐久高于 30 的备用镐子，自动挖矿已停止。");
                     return;
                  }
               }

               if (this.phase == AutoMineManager.Phase.REPAIR) {
                  this.tickRepair(mc, player, bestPick >= 0 ? bestPick : pickSlot);
                  return;
               }

               if (this.phase == AutoMineManager.Phase.WORK && this.shouldRepair(player, bestPick >= 0 ? bestPick : pickSlot, level)) {
                  this.taskBeforeRepair = new LinkedHashSet<>(this.task);
                  this.phase = AutoMineManager.Phase.REPAIR;
                  this.repairTicks = 0;
                  this.safeCancel();

                  try {
                     BaritoneBridge.start(Blocks.NETHER_QUARTZ_ORE);
                  } catch (Throwable t) {
                     LOGGER.error("[automine] failed to start quartz repair mining", t);
                  }

                  return;
               }

               this.tickWork(mc, player, level);
            } catch (Throwable e) {
               LOGGER.error("[automine] tick error", e);
            }
         }
      }
   }

   private void resumeMining() {
      if (!this.task.isEmpty()) {
         this.lastInjected = new HashSet<>();
         this.lastInjectPos = null;
         this.stuckTicks = 0;
         this.lastStuckCheckPos = null;
         this.seedTarget = null;
         this.seedMining = false;
         this.seedTargetTicks = 0;
         this.seedMineTicks = 0;
         this.seedSettleTicks = 0;
      }
   }

   /** 存盒结束后由 ShulkerAutoStore 调用：Baritone 已被取消，矿脉状态需要重新扫描。 */
   public void onShulkerStoreFinished() {
      this.resumeMining();
      this.seedVeinActive = false;
      this.seedVein = null;
      this.seedIdleTicks = 0;
      this.seedStuckTicks = 0;
      this.seedCollectPos = null;
      this.seedCollectRepathTicks = 0;
      this.seedRescanTicks = 0;
      this.seedEscapeTicks = 0;
   }

   private void tickWork(Minecraft mc, LocalPlayer player, Level level) {
      boolean overworld = player.level().dimension() == Level.OVERWORLD;
      if (player.isInLava()) {
         mc.options.keyAttack.setDown(false);
         if (this.seedTarget != null) {
            this.seedUnreachable.add(this.seedTarget.asLong());
         }

         this.seedTarget = null;
         this.seedMining = false;
         this.seedCollectPos = null;
         this.seedSettleTicks = 0;
         this.safeCancel();

         try {
            BaritoneBridge.climbTo((int)Math.floor(player.getY()) + 4);
         } catch (Throwable var14) {
         }

         this.seedEscapeTicks = 60;
         this.throttledInfo("自动挖矿：掉进岩浆，正在撤离并拉黑该目标");
      } else if (this.seedEscapeTicks > 0) {
         this.seedEscapeTicks--;
      } else {
         LiquidRadar.update(level, player.blockPosition());
         if (!player.isInLava() && !player.isOnFire()) {
            Set<Block> wantedBlocks = Set.of(this.blocksFor(this.task));
            if (this.seedTaskCache != this.task) {
               this.seedTaskCache = this.task;
               this.seedDonePos.clear();
               this.seedTarget = null;
               this.seedTargetTicks = 0;
               this.seedIdleTicks = 0;
               this.seedStuckTicks = 0;
               this.seedVein = null;
               this.seedVeinActive = false;
            }

            if (this.seedVeinActive) {
               boolean mining = BaritoneBridge.isMining();
               boolean pathing = BaritoneBridge.isPathing();
               int known = BaritoneBridge.getKnownOreCount();
               if (!mining) {
                  for (BlockPos p : this.seedVein) {
                     this.seedDonePos.add(p.asLong());
                  }

                  this.seedVein = null;
                  this.seedVeinActive = false;
                  this.seedStuckTicks = 0;
                  this.seedIdleTicks = 0;
                  this.throttledInfo("自动挖矿：矿脉挖完，寻找下一条");
               } else if (known == 0) {
                  if (++this.seedIdleTicks > 20) {
                     for (BlockPos p : this.seedVein) {
                        this.seedDonePos.add(p.asLong());
                     }

                     this.seedVein = null;
                     this.seedVeinActive = false;
                     this.seedIdleTicks = 0;
                     this.safeCancel();
                  }
               } else {
                  this.seedIdleTicks = 0;
                  if (!pathing) {
                     long wallMs = System.currentTimeMillis();
                     if (wallMs - this.seedVeinStartMs >= 6000L && ++this.seedStuckTicks > 480) {
                        this.seedStuckTicks = 0;

                        for (BlockPos p : this.seedVein) {
                           this.seedDonePos.add(p.asLong());
                        }

                        this.seedVein = null;
                        this.seedVeinActive = false;
                        this.safeCancel();
                        this.throttledInfo("自动挖矿：挖矿停滞，已放弃该矿脉");
                     }
                  } else {
                     this.seedStuckTicks = 0;
                  }
               }
            } else {
               if (this.seedVein == null) {
                  this.seedVein = this.selectNearestVein(player, wantedBlocks, overworld);
                  this.seedStuckTicks = 0;
                  this.seedIdleTicks = 0;
                  this.seedTargetTicks = 0;
                  if (this.seedVein == null) {
                     if (BaritoneBridge.isMining()) {
                        this.safeCancel();
                     }

                     this.throttledInfo("自动挖矿：附近没有更多种子矿物");
                     return;
                  }
               }

               List<BlockPos> injectable = new ArrayList<>();
               BlockPos guideAnchor = null;
               double guideSq = Double.MAX_VALUE;

               for (BlockPos pos : this.seedVein) {
                  if (level.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) != null) {
                     BlockState st = level.getBlockState(pos);
                     if (st.isAir()) {
                        this.seedDonePos.add(pos.asLong());
                     } else if (st.getDestroySpeed(level, pos) < 0.0F) {
                        this.seedDonePos.add(pos.asLong());
                     } else if (this.touchesLiquid(level, pos)) {
                        this.seedDonePos.add(pos.asLong());
                     } else {
                        injectable.add(pos);
                     }
                  } else {
                     double dSq = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                     if (dSq < guideSq) {
                        guideSq = dSq;
                        guideAnchor = pos;
                     }
                  }
               }

               if (injectable.size() > 1) {
                  injectable.sort(Comparator.comparingDouble(px -> player.distanceToSqr(px.getX() + 0.5, px.getY() + 0.5, px.getZ() + 0.5)));
                  if (injectable.size() > 16) {
                     injectable = new ArrayList<>(injectable.subList(0, 16));
                  }
               }

               if (!injectable.isEmpty()) {
                  BaritoneBridge.clearCustomGoal();
                  this.seedTarget = null;
                  this.seedTargetTicks = 0;

                  try {
                     BaritoneBridge.cancel();
                     BaritoneBridge.start(this.blocksFor(this.task));
                     if (!BaritoneBridge.injectOres(injectable)) {
                        LOGGER.warn("[automine] ore injection failed, Baritone falls back to its own scan");
                     }

                     this.seedVeinActive = true;
                     this.seedVeinStartMs = System.currentTimeMillis();
                     this.throttledInfo("自动挖矿：注入矿脉 " + injectable.size() + " 块，开始挖掘");
                  } catch (Throwable t) {
                     LOGGER.error("[automine] vein start failed", t);

                     for (BlockPos p : this.seedVein) {
                        this.seedDonePos.add(p.asLong());
                     }

                     this.seedVein = null;
                  }
               } else if (guideAnchor == null) {
                  this.seedVein = null;
               } else if (BaritoneBridge.hasCustomGoal()) {
                  if (++this.seedTargetTicks > 600) {
                     for (BlockPos p : this.seedVein) {
                        this.seedDonePos.add(p.asLong());
                     }

                     this.seedVein = null;
                     this.seedTarget = null;
                     this.seedTargetTicks = 0;
                     BaritoneBridge.clearCustomGoal();
                     this.throttledInfo("自动挖矿：矿脉无法接近，换下一处");
                  }
               } else {
                  this.seedTarget = guideAnchor;
                  this.seedTargetTicks = 0;
                  this.throttledInfo("自动挖矿：前往矿脉 " + guideAnchor.getX() + " " + guideAnchor.getY() + " " + guideAnchor.getZ());

                  try {
                     BaritoneBridge.cancel();
                     BaritoneBridge.gotoNear(guideAnchor, overworld);
                  } catch (Throwable t) {
                     LOGGER.error("[automine] guide goal failed", t);

                     for (BlockPos p : this.seedVein) {
                        this.seedDonePos.add(p.asLong());
                     }

                     this.seedVein = null;
                     this.seedTarget = null;
                  }
               }
            }
         } else {
            this.safeCancel();
            this.seedEscapeTicks = 60;
            this.seedStuckTicks = 0;
            this.seedVeinActive = false;
            this.throttledInfo("自动挖矿：触及危险区域，已暂停 3 秒");
         }
      }
   }

   private List<BlockPos> selectNearestVein(LocalPlayer player, Set<Block> wantedBlocks, boolean overworld) {
      int pcx = player.blockPosition().getX() >> 4;
      int pcz = player.blockPosition().getZ() >> 4;
      int chunkRadius = Math.max(1, OreXrayManager.INSTANCE.renderDistance);
      List<BlockPos> cands = new ArrayList<>();

      for (OreXrayManager.PredictedOre p : OreXrayManager.INSTANCE.getPredictedOres()) {
         if (wantedBlocks.contains(p.block())) {
            BlockPos pos = p.pos().immutable();
            if (Math.abs((pos.getX() >> 4) - pcx) <= chunkRadius && Math.abs((pos.getZ() >> 4) - pcz) <= chunkRadius) {
               long key = pos.asLong();
               if (!this.seedUnreachable.contains(key) && !this.seedDonePos.contains(key)) {
                  cands.add(pos);
               }
            }
         }
      }

      int n = cands.size();
      if (n == 0) {
         return null;
      }

      boolean[] used = new boolean[n];
      List<BlockPos> best = null;
      double bestSq = Double.MAX_VALUE;

      for (int i = 0; i < n; i++) {
         if (!used[i]) {
            List<BlockPos> vein = new ArrayList<>();
            ArrayDeque<Integer> stack = new ArrayDeque<>();
            used[i] = true;
            stack.push(i);

            while (!stack.isEmpty()) {
               BlockPos a = cands.get(stack.pop());
               vein.add(a);

               for (int b = 0; b < n; b++) {
                  if (!used[b]) {
                     BlockPos c = cands.get(b);
                     long dx = a.getX() - c.getX();
                     long dy = a.getY() - c.getY();
                     long dz = a.getZ() - c.getZ();
                     if (dx * dx + dy * dy + dz * dz <= 64L) {
                        used[b] = true;
                        stack.push(b);
                     }
                  }
               }
            }

            double veinSq = Double.MAX_VALUE;

            for (BlockPos pos : vein) {
               double dSq = player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
               if (dSq < veinSq) {
                  veinSq = dSq;
               }
            }

            if (veinSq < bestSq) {
               bestSq = veinSq;
               best = vein;
            }
         }
      }

      return best;
   }

   private void safeCancel() {
      if (FabricLoader.getInstance().isModLoaded("baritone")) {
         try {
            BaritoneBridge.cancel();
         } catch (Throwable t) {
            LOGGER.error("[automine] cancel failed", t);
         }
      }
   }

   private boolean touchesLiquid(Level level, BlockPos pos) {
      for (Direction d : Direction.values()) {
         BlockState st = level.getBlockState(pos.relative(d));
         if (st.is(Blocks.LAVA) || st.is(Blocks.WATER)) {
            return true;
         }
      }

      return false;
   }

   private boolean hasLineOfSight(Level level, LocalPlayer player, BlockPos pos) {
      Vec3 eye = player.getEyePosition();
      double x = pos.getX() + 0.5;
      double y = pos.getY() + 0.5;
      double z = pos.getZ() + 0.5;
      double[][] pts = new double[][]{{x, y, z}, {x + 0.51, y, z}, {x - 0.51, y, z}, {x, y + 0.51, z}, {x, y - 0.51, z}, {x, y, z + 0.51}, {x, y, z - 0.51}};

      for (double[] pt : pts) {
         BlockHitResult hit = level.clip(
            new ClipContext(eye, new Vec3(pt[0], pt[1], pt[2]), net.minecraft.world.level.ClipContext.Block.COLLIDER, Fluid.NONE, player)
         );
         if (hit.getType() == Type.MISS || hit.getBlockPos().equals(pos)) {
            return true;
         }
      }

      return false;
   }

   private void faceBlock(LocalPlayer player, BlockPos pos) {
      double dx = pos.getX() + 0.5 - player.getX();
      double dy = pos.getY() + 0.5 - player.getEyeY();
      double dz = pos.getZ() + 0.5 - player.getZ();
      double horiz = Math.sqrt(dx * dx + dz * dz);
      float yaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
      float pitch = (float)(-Math.toDegrees(Math.atan2(dy, horiz)));
      player.setYRot(yaw);
      player.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
   }

   private void releaseInputs() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.options != null) {
         mc.options.keyAttack.setDown(false);
         mc.options.keyUse.setDown(false);
         mc.options.keyUp.setDown(false);
         mc.options.keyDown.setDown(false);
         mc.options.keyLeft.setDown(false);
         mc.options.keyRight.setDown(false);
         mc.options.keyJump.setDown(false);
         mc.options.keyShift.setDown(false);
         mc.options.keySprint.setDown(false);
      }
   }

   private void releaseMovementKeys() {
      Minecraft mc = Minecraft.getInstance();
      if (mc.options != null) {
         mc.options.keyAttack.setDown(false);
         mc.options.keyUse.setDown(false);
         mc.options.keyUp.setDown(false);
         mc.options.keyDown.setDown(false);
         mc.options.keyLeft.setDown(false);
         mc.options.keyRight.setDown(false);
         mc.options.keyJump.setDown(false);
         mc.options.keyShift.setDown(false);
         mc.options.keySprint.setDown(false);
      }
   }

   private void walkToward(Minecraft mc, LocalPlayer player, BlockPos pos) {
      if (pos == null) {
         this.releaseMovementKeys();
      } else {
         double dx = pos.getX() + 0.5 - player.getX();
         double dz = pos.getZ() + 0.5 - player.getZ();
         float yaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
         player.setYRot(yaw);
         mc.options.keyUp.setDown(true);
         mc.options.keySprint.setDown(true);
      }
   }

   private void handleDeath(Minecraft mc) {
      ShulkerAutoStore.INSTANCE.cancel();
      this.safeCancel();
      if (mc.getConnection() != null) {
         mc.getConnection().send(new ServerboundClientCommandPacket(Action.PERFORM_RESPAWN));
      }

      if (Config.get().autoMineAutoReturn && !this.backPending) {
         this.backPending = true;
         this.backDelayTicks = 50;
         this.info("已死亡，自动复活中，稍后发送 /back 返回。");
      } else {
         this.info("已死亡，自动复活中。");
      }
   }

   private boolean shouldRepair(LocalPlayer player, int pickSlot, Level level) {
      return !this.repairPossible(player, pickSlot, level) ? false : this.currentDurability(player, pickSlot) <= 20;
   }

   private boolean repairPossible(LocalPlayer player, int pickSlot, Level level) {
      if (this.task.contains(AutoMineManager.Task.DEBRIS)) {
         return false;
      } else if (level.dimension().identifier().getPath().toLowerCase().contains("nether")
         || level.dimensionType().coordinateScale() == 8.0 && !level.dimensionType().hasSkyLight()) {
         ItemStack pick = player.getInventory().getItem(pickSlot);
         return pick.isEmpty() ? false : this.hasMending(pick);
      } else {
         return false;
      }
   }

   private int currentDurability(LocalPlayer player, int pickSlot) {
      return this.remainingDurability(player.getInventory().getItem(pickSlot));
   }

   private int remainingDurability(ItemStack stack) {
      return stack != null && !stack.isEmpty() ? stack.getMaxDamage() - stack.getDamageValue() : 0;
   }

   private boolean isPickaxe(ItemStack stack) {
      return stack != null && !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("_pickaxe");
   }

   private int bestHotbarPickaxeSlot(LocalPlayer player) {
      int best = -1;
      int bestRem = -1;

      for (int i = 0; i < 9; i++) {
         ItemStack s = player.getInventory().getItem(i);
         if (this.isPickaxe(s)) {
            int rem = this.remainingDurability(s);
            if (rem > bestRem) {
               bestRem = rem;
               best = i;
            }
         }
      }

      return best;
   }

   private int findEmptyHotbarSlot(LocalPlayer player) {
      for (int i = 0; i < 9; i++) {
         if (player.getInventory().getItem(i).isEmpty()) {
            return i;
         }
      }

      return -1;
   }

   private int findSparePickaxeMenuSlot(LocalPlayer player) {
      int best = -1;
      int bestRem = 30;

      for (int i = 9; i < player.getInventory().getContainerSize(); i++) {
         ItemStack s = player.getInventory().getItem(i);
         if (this.isPickaxe(s)) {
            int rem = this.remainingDurability(s);
            if (rem > bestRem) {
               bestRem = rem;
               best = i;
            }
         }
      }

      return best < 0 ? -1 : this.menuSlotOfInventoryIndex(best);
   }

   private boolean hasMending(ItemStack stack) {
      ItemEnchantments ench = (ItemEnchantments)stack.get(DataComponents.ENCHANTMENTS);
      if (ench == null) {
         return false;
      }

      for (Holder<Enchantment> holder : ench.keySet()) {
         if (holder.is(Identifier.parse("minecraft:mending")) && ench.getLevel(holder) > 0) {
            return true;
         }
      }

      return false;
   }

   private void tickRepair(Minecraft mc, LocalPlayer player, int pickSlot) {
      this.repairTicks++;
      if (this.reissueCooldown > 0) {
         this.reissueCooldown--;
      } else if (!BaritoneBridge.isMining()) {
         try {
            BaritoneBridge.start(Blocks.NETHER_QUARTZ_ORE);
         } catch (Throwable t) {
            LOGGER.error("[automine] failed to restart quartz repair mining", t);
         }

         this.reissueCooldown = 100;
      }

      ItemStack pick = player.getInventory().getItem(pickSlot);
      int dur = pick.isEmpty() ? 0 : pick.getMaxDamage() - pick.getDamageValue();
      int maxDur = pick.isEmpty() ? 1 : pick.getMaxDamage();
      if (dur >= 0.8F * maxDur) {
         this.finishRepair(true);
      } else if (this.repairTicks >= 600) {
         this.finishRepair(false);
      }
   }

   private void finishRepair(boolean ok) {
      this.phase = AutoMineManager.Phase.WORK;
      this.repairTicks = 0;
      this.reissueCooldown = 0;
      if (ok) {
      }

      if (!this.taskBeforeRepair.isEmpty()) {
         this.task.clear();
         this.task.addAll(this.taskBeforeRepair);
      }

      this.taskBeforeRepair.clear();
      this.resumeMining();
   }

   private void tickEat(Minecraft mc, LocalPlayer player) {
      this.releaseMovementKeys();
      if (this.eatTicks == 0) {
         // 与全局自动进食共用同一套挑选策略：不吃有害/贵重食物，优先选刚好补满的。
         int slot = AutoEatManager.foodSlot(player);
         if (slot < 0) {
            this.finishEat(mc, player);
            return;
         }

         if (slot < 9) {
            player.getInventory().setSelectedSlot(slot);
         } else {
            // 背包主格 9..35 的 menu 槽号与物品栏下标一致。
            mc.gameMode.handleContainerInput(0, slot, player.getInventory().getSelectedSlot(), ContainerInput.SWAP, player);
            this.eatSwapMenuSlot = slot;
         }
      }

      this.eatTicks++;
      mc.options.keyUse.setDown(true);
      if (this.eatTicks > 40 && !player.isUsingItem()) {
         this.finishEat(mc, player);
      } else {
         if (this.eatTicks > 300) {
            mc.gameMode.releaseUsingItem(player);
            this.finishEat(mc, player);
         }
      }
   }

   private void finishEat(Minecraft mc, LocalPlayer player) {
      mc.options.keyUse.setDown(false);
      if (this.eatSwapMenuSlot >= 0) {
         mc.gameMode.handleContainerInput(0, this.eatSwapMenuSlot, player.getInventory().getSelectedSlot(), ContainerInput.SWAP, player);
         this.eatSwapMenuSlot = -1;
      }

      this.eatTicks = 0;
      this.phase = AutoMineManager.Phase.WORK;
      this.resumeMining();
   }

   public static boolean isPickupWhitelisted(String itemId) {
      return INSTANCE.task.isEmpty() ? true : INSTANCE.isTaskDrop(itemId);
   }





   private boolean isTaskDrop(String itemId) {
      for (AutoMineManager.Task t : this.task) {
         switch (t) {
            case DIAMOND:
               if (itemId.equals("diamond")) {
                  return true;
               }
               break;
            case DEBRIS:
               if (itemId.equals("ancient_debris") || itemId.equals("netherite_scrap")) {
                  return true;
               }
               break;
            case IRON:
               if (itemId.equals("raw_iron")) {
                  return true;
               }
               break;
            case GOLD:
               if (itemId.equals("raw_gold")) {
                  return true;
               }
               break;
            case COAL:
               if (itemId.equals("coal")) {
                  return true;
               }
               break;
            case REDSTONE:
               if (itemId.equals("redstone")) {
                  return true;
               }
               break;
            case LAPIS:
               if (itemId.equals("lapis_lazuli")) {
                  return true;
               }
               break;
            case COPPER:
               if (itemId.equals("raw_copper")) {
                  return true;
               }
               break;
            case EMERALD:
               if (itemId.equals("emerald")) {
                  return true;
               }
         }
      }

      return false;
   }

















   private int menuSlotOfInventoryIndex(int invIndex) {
      return invIndex < 9 ? invIndex + 36 : invIndex;
   }

   private int findPickaxeSlot(LocalPlayer player) {
      for (int i = 0; i < 9; i++) {
         ItemStack stack = player.getInventory().getItem(i);
         if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("_pickaxe")) {
            return i;
         }
      }

      return -1;
   }

   private void tickCombat(Minecraft mc, LocalPlayer player, Level level) {
      if (level instanceof ClientLevel clientLevel) {
         LivingEntity target = null;
         double bestSq = 12.25;

         for (Entity e : clientLevel.entitiesForRendering()) {
            if (e instanceof LivingEntity living && living != player && living.isAlive() && living instanceof Enemy) {
               double d = player.distanceToSqr(living);
               if (!(d > bestSq) && player.hasLineOfSight(living)) {
                  bestSq = d;
                  target = living;
               }
            }
         }

         if (target != null) {
            this.faceEntity(player, target);
            if (player.getAttackStrengthScale(0.0F) >= 0.9F) {
               mc.gameMode.attack(player, target);
               player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, false);
            }
         }
      }
   }

   private void faceEntity(LocalPlayer player, Entity target) {
      double dx = target.getX() - player.getX();
      double dz = target.getZ() - player.getZ();
      double dy = target.getY() + target.getBbHeight() * 0.5 - player.getEyeY();
      double horiz = Math.sqrt(dx * dx + dz * dz);
      float yaw = (float)(Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
      float pitch = (float)(-Math.toDegrees(Math.atan2(dy, horiz)));
      player.setYRot(yaw);
      player.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
   }

   private void halt(String message) {
      this.stop();
      this.info(message);
   }

   private void info(String message) {
      LOGGER.info("[automine] {}", message);
      ClientCommand.sendFeedback("[自动挖矿] " + message, ChatFormatting.GOLD);
   }

   private void throttledInfo(String message) {
      long now = System.currentTimeMillis();
      if (now - this.lastInfoMs >= 3000L) {
         this.lastInfoMs = now;
         this.info(message);
      }
   }

   private enum Phase {
      WORK,
      EAT,
      FREEZE,
      REPAIR,
      SHULKER;
   }


   public enum Task {
      OFF,
      DIAMOND,
      DEBRIS,
      IRON,
      GOLD,
      COAL,
      REDSTONE,
      LAPIS,
      COPPER,
      EMERALD;
   }
}
