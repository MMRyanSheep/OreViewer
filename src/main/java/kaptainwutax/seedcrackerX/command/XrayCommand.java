package kaptainwutax.seedcrackerX.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import kaptainwutax.seedcrackerX.xray.OreXrayManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;

public class XrayCommand extends ClientCommand {
   private static final String[] ALL = new String[]{"钻石", "远古残骸", "铁", "金", "煤", "红石", "青金石", "铜", "绿宝石"};

   @Override
   public String getName() {
      return "xray";
   }

   @Override
   public void build(LiteralArgumentBuilder<FabricClientCommandSource> builder) {
      builder.executes(context -> this.showStatus());
      builder.then(
         ClientCommands.argument("args", StringArgumentType.greedyString()).executes(context -> this.setOres(StringArgumentType.getString(context, "args")))
      );
   }

   private String alias(String name) {
      switch (name.trim().toLowerCase()) {
         case "钻石":
         case "diamond":
            return "钻石";
         case "远古残骸":
         case "残骸":
         case "下界合金":
         case "debris":
            return "远古残骸";
         case "铁":
         case "铁矿":
         case "iron":
            return "铁";
         case "金":
         case "金矿":
         case "gold":
            return "金";
         case "煤":
         case "煤矿":
         case "煤炭":
         case "coal":
            return "煤";
         case "红石":
         case "红石矿":
         case "redstone":
            return "红石";
         case "青金石":
         case "青金":
         case "lapis":
            return "青金石";
         case "铜":
         case "铜矿":
         case "copper":
            return "铜";
         case "绿宝石":
         case "翡翠":
         case "emerald":
            return "绿宝石";
         default:
            return null;
      }
   }

   private static String rangeText() {
      int r = OreXrayManager.INSTANCE.getXrayRadius();
      return r > 0 ? r + " 格" : "已取消限制（扫整个渲染距离）";
   }

   private static boolean isOn(String s) {
      if (s == null) {
         return false;
      }

      String a = s.trim().toLowerCase();
      return a.equals("on")
         || a.equals("1")
         || a.equals("true")
         || a.equals("open")
         || a.equals("enable")
         || s.equals("开启")
         || s.equals("打开")
         || s.equals("启用")
         || s.equals("开");
   }

   private static boolean isOff(String s) {
      if (s == null) {
         return false;
      }

      String a = s.trim().toLowerCase();
      return a.equals("off") || a.equals("0") || a.equals("false") || a.equals("close") || a.equals("disable") || s.equals("关闭") || s.equals("关");
   }

   private int showStatus() {
      Set<String> on = OreXrayManager.INSTANCE.getXrayCmdOres();
      if (on.isEmpty()) {
         sendFeedback("[xray] 独立透视未开启。用法：/os xray <矿物名...|全部> [on|off]", ChatFormatting.GRAY);
      } else {
         sendFeedback("[xray] 独立透视中：" + String.join("、", on), ChatFormatting.GREEN);
      }

      sendFeedback("[xray] 本线路直接读客户端已加载区块的方块数据（与 Baritone 矿位扫描同源），无需种子，与 /os xs 互不影响。", ChatFormatting.GRAY);
      sendFeedback("[xray] 6格延伸（假矿服用）：/os xray 延伸 on|off，当前=" + (OreXrayManager.INSTANCE.isXrayCmdExtend() ? "开" : "关"), ChatFormatting.GRAY);
      sendFeedback("[xray] 扫描范围：/os xray 范围 <格数|关>，当前=" + rangeText(), ChatFormatting.GRAY);
      sendFeedback("[xray] 可用矿物：钻石/远古残骸/铁/金/煤/红石/青金石/铜/绿宝石（/os xray 停止 = 全关）", ChatFormatting.GRAY);
      return 0;
   }

   private int setOres(String raw) {
      String[] parts = raw.trim().split("\\s+");
      if (parts.length < 1 || !parts[0].equals("延伸") && !parts[0].equals("扩展") && !parts[0].equalsIgnoreCase("extend")) {
         if (parts.length < 1 || !parts[0].equals("范围") && !parts[0].equals("半径") && !parts[0].equalsIgnoreCase("range")) {
            boolean enable = true;
            List<String> names = new ArrayList<>();

            for (String p : parts) {
               if (!p.isEmpty()) {
                  if (isOn(p)) {
                     enable = true;
                  } else if (isOff(p)) {
                     enable = false;
                  } else {
                     names.add(p);
                  }
               }
            }

            if (names.size() == 1 && (names.get(0).equals("停止") || names.get(0).equals("关闭全部"))) {
               enable = false;
               names.set(0, "全部");
            }

            StringBuilder invalid = new StringBuilder();
            List<String> targets = new ArrayList<>();

            for (String n : names) {
               if (!n.equals("全部") && !n.equals("所有") && !n.equalsIgnoreCase("all")) {
                  String key = this.alias(n);
                  if (key != null) {
                     if (!targets.contains(key)) {
                        targets.add(key);
                     }
                  } else {
                     if (invalid.length() > 0) {
                        invalid.append("、");
                     }

                     invalid.append(n);
                  }
               } else {
                  for (String k : ALL) {
                     if (!targets.contains(k)) {
                        targets.add(k);
                     }
                  }
               }
            }

            if (targets.isEmpty()) {
               sendFeedback("[xray] 用法：/os xray <矿物名...|全部> [on|off]，无参数显示当前状态。可用矿物：钻石/远古残骸/铁/金/煤/红石/青金石/铜/绿宝石", ChatFormatting.RED);
               if (invalid.length() > 0) {
                  sendFeedback("[xray] 未识别的矿物：" + invalid, ChatFormatting.YELLOW);
               }

               return 0;
            } else {
               if (invalid.length() > 0) {
                  sendFeedback("[xray] 已忽略未识别的矿物：" + invalid, ChatFormatting.YELLOW);
               }

               StringBuilder changed = new StringBuilder();

               for (String key : targets) {
                  OreXrayManager.INSTANCE.setXrayCmdOre(key, enable);
                  if (changed.length() > 0) {
                     changed.append("、");
                  }

                  changed.append(key);
               }

               sendFeedback("[xray] 已" + (enable ? "开启" : "关闭") + "：" + changed, enable ? ChatFormatting.GREEN : ChatFormatting.GOLD);
               if (enable) {
                  sendFeedback("[xray] 独立透视：直接读客户端已加载区块的矿位。/os xray 范围 关 = 扫整个渲染距离；/os xray 延伸 on 加画真矿周围6格。", ChatFormatting.GRAY);
               }

               return 0;
            }
         } else {
            if (parts.length < 2) {
               sendFeedback("[xray] 当前范围=" + rangeText() + "。用法：/os xray 范围 <格数>（如 16）；/os xray 范围 关 = 取消限制（扫整个渲染距离）", ChatFormatting.GRAY);
               return 0;
            }

            String v = parts[1].trim();
            if (!isOff(v) && !v.equals("取消") && !v.equals("全部") && !v.equals("不限") && !v.equals("无")) {
               try {
                  int n = Integer.parseInt(v);
                  OreXrayManager.INSTANCE.setXrayRadius(n);
                  sendFeedback("[xray] 范围已设为 " + n + " 格（0 或 关 = 取消限制）", ChatFormatting.GREEN);
               } catch (NumberFormatException ex) {
                  sendFeedback("[xray] 无效的范围：" + v + "。用法：/os xray 范围 <格数> 或 /os xray 范围 关", ChatFormatting.RED);
               }

               return 0;
            } else {
               OreXrayManager.INSTANCE.setXrayRadius(0);
               sendFeedback("[xray] 已取消范围限制：改为扫描整个渲染距离内的全部已加载区块（与 Baritone 矿位扫描同源）", ChatFormatting.GOLD);
               return 0;
            }
         }
      } else if (parts.length < 2) {
         sendFeedback("[xray] 6格延伸当前=" + (OreXrayManager.INSTANCE.isXrayCmdExtend() ? "开" : "关") + "。用法：/os xray 延伸 on|off（假矿服开启）", ChatFormatting.GRAY);
         return 0;
      } else {
         boolean on = isOn(parts[1]);
         OreXrayManager.INSTANCE.setXrayCmdExtend(on);
         sendFeedback("[xray] 6格延伸已" + (on ? "开启（加画真矿周围 6 格，疑似同矿脉被伪装部分）" : "关闭（只画真矿本身）"), on ? ChatFormatting.GREEN : ChatFormatting.GOLD);
         return 0;
      }
   }
}
