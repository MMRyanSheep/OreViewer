package kaptainwutax.seedcrackerX.init;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import java.util.ArrayList;
import java.util.List;
import kaptainwutax.seedcrackerX.command.AutoEatCommand;
import kaptainwutax.seedcrackerX.command.AutoMineCommand;
import kaptainwutax.seedcrackerX.command.BuffCommand;
import kaptainwutax.seedcrackerX.command.ClientCommand;
import kaptainwutax.seedcrackerX.command.CrackerCommand;
import kaptainwutax.seedcrackerX.command.DataCommand;
import kaptainwutax.seedcrackerX.command.DatabaseCommand;
import kaptainwutax.seedcrackerX.command.DxCommand;
import kaptainwutax.seedcrackerX.command.ElytraAliasCommand;
import kaptainwutax.seedcrackerX.command.ElytraCommand;
import kaptainwutax.seedcrackerX.command.FinderCommand;
import kaptainwutax.seedcrackerX.command.FkCommand;
import kaptainwutax.seedcrackerX.command.FlyCommand;
import kaptainwutax.seedcrackerX.command.GuiCommand;
import kaptainwutax.seedcrackerX.command.HelpCommand;
import kaptainwutax.seedcrackerX.command.KickstartCommand;
import kaptainwutax.seedcrackerX.command.MdcCommand;
import kaptainwutax.seedcrackerX.command.ProCommand;
import kaptainwutax.seedcrackerX.command.RenderCommand;
import kaptainwutax.seedcrackerX.command.SeedCommand;
import kaptainwutax.seedcrackerX.command.ShulkerCommand;
import kaptainwutax.seedcrackerX.command.VersionCommand;
import kaptainwutax.seedcrackerX.command.XrayCommand;
import kaptainwutax.seedcrackerX.command.XraySettingsCommand;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;

public class ClientCommands {
   public static final String PREFIX = "os";
   public static final List<ClientCommand> COMMANDS = new ArrayList<>();
   public static RenderCommand RENDER;
   public static FinderCommand FINDER;
   public static DataCommand DATA;
   public static CrackerCommand CRACKER;
   public static VersionCommand VERSION;
   public static GuiCommand GUI;
   public static DatabaseCommand DATABASE;
   public static KickstartCommand KICKSTART;
   public static SeedCommand SEED;
   public static XraySettingsCommand XRAY_SETTINGS;
   public static AutoMineCommand AUTO_MINE;
   public static XrayCommand XRAY;
   public static MdcCommand MDC;
   public static DxCommand DX;
   public static FkCommand FK;
   public static HelpCommand HELP;
   public static BuffCommand BUFF;

   public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
      COMMANDS.forEach(clientCommand -> clientCommand.register(dispatcher));
      dispatcher.register(
         (LiteralArgumentBuilder)net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal("os")
            .then(net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument("args", StringArgumentType.greedyString()).executes(context -> {
               ClientCommand.sendFeedback("未知指令，请检查拼写", ChatFormatting.RED);
               return 1;
            }))
      );
   }

   static {
      COMMANDS.add(RENDER = new RenderCommand());
      COMMANDS.add(FINDER = new FinderCommand());
      COMMANDS.add(DATA = new DataCommand());
      COMMANDS.add(CRACKER = new CrackerCommand());
      COMMANDS.add(VERSION = new VersionCommand());
      COMMANDS.add(GUI = new GuiCommand());
      COMMANDS.add(DATABASE = new DatabaseCommand());
      COMMANDS.add(KICKSTART = new KickstartCommand());
      COMMANDS.add(SEED = new SeedCommand());
      COMMANDS.add(XRAY_SETTINGS = new XraySettingsCommand());
      COMMANDS.add(AUTO_MINE = new AutoMineCommand());
      COMMANDS.add(XRAY = new XrayCommand());
      COMMANDS.add(MDC = new MdcCommand());
      COMMANDS.add(DX = new DxCommand());
      COMMANDS.add(FK = new FkCommand());
      COMMANDS.add(HELP = new HelpCommand());
      COMMANDS.add(BUFF = new BuffCommand());
      COMMANDS.add(new ProCommand());
      COMMANDS.add(new FlyCommand());
      COMMANDS.add(new ElytraCommand());
      COMMANDS.add(new ElytraAliasCommand());
      COMMANDS.add(new ShulkerCommand());
      COMMANDS.add(new AutoEatCommand());
   }
}
