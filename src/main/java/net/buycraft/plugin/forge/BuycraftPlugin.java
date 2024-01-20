package net.buycraft.plugin.forge;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.logging.LogUtils;
import net.buycraft.plugin.BuyCraftAPI;
import net.buycraft.plugin.IBuycraftPlatform;
import net.buycraft.plugin.data.QueuedPlayer;
import net.buycraft.plugin.data.responses.ServerInformation;
import net.buycraft.plugin.execution.DuePlayerFetcher;
import net.buycraft.plugin.execution.placeholder.NamePlaceholder;
import net.buycraft.plugin.execution.placeholder.PlaceholderManager;
import net.buycraft.plugin.execution.placeholder.UuidPlaceholder;
import net.buycraft.plugin.execution.strategy.CommandExecutor;
import net.buycraft.plugin.execution.strategy.PostCompletedCommandsTask;
import net.buycraft.plugin.execution.strategy.QueuedCommandExecutor;
import net.buycraft.plugin.forge.command.*;
import net.buycraft.plugin.forge.util.VersionCheck;
import net.buycraft.plugin.shared.Setup;
import net.buycraft.plugin.shared.config.BuycraftConfiguration;
import net.buycraft.plugin.shared.tasks.PlayerJoinCheckTask;
import net.buycraft.plugin.shared.util.AnalyticsSend;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import okhttp3.OkHttpClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Predicate;
import java.util.logging.Level;

@Mod("tebexforge")
public class BuycraftPlugin {

    private static final Logger LOGGER = LogManager.getLogger("Tebex");

    private final String pluginVersion;

    private final PlaceholderManager placeholderManager = new PlaceholderManager();
    private static final BuycraftConfiguration configuration = new BuycraftConfiguration();
    private final Path baseDirectory = Paths.get("config", "buycraft");

    private final List<ForgeScheduledTask> scheduledTasks = new ArrayList<>();

    private MinecraftServer server;
    private static ScheduledExecutorService executor;

    private BuyCraftAPI apiClient;
    private DuePlayerFetcher duePlayerFetcher;
    private ServerInformation serverInformation;
    private OkHttpClient httpClient;
    private IBuycraftPlatform platform;
    private CommandExecutor commandExecutor;
    //private BuycraftI18n i18n;
    private PostCompletedCommandsTask completedCommandsTask;
    private PlayerJoinCheckTask playerJoinCheckTask;

    private static BuycraftPlugin plugin;

    private boolean stopped = false;

    public BuycraftPlugin() {
        pluginVersion = ModLoadingContext.get().getActiveContainer().getModInfo().getVersion().toString();
        MinecraftForge.EVENT_BUS.register(this);
    }

    public static class Debug implements Predicate<RunnableScheduledFuture<?>> {

        @Override
        public boolean test(RunnableScheduledFuture<?> runnableScheduledFuture) {
            return !runnableScheduledFuture.isPeriodic();
        }
    }

    @SubscribeEvent
    public static void onCommandRegister(RegisterCommandsEvent event) {
        //region Commands
        event.getDispatcher().register(configureCommand(Commands.literal("tebex")));
        event.getDispatcher().register(configureCommand(Commands.literal("buycraft")));

        if (!configuration.isDisableBuyCommand()) {
            configuration.getBuyCommandName().forEach(cmd ->
                    event.getDispatcher().register(Commands.literal(cmd).executes(new BuyCommand(plugin))));
        }
        //endregion

    }

    // As close to an onEnable as we are ever going to get :(
    @SubscribeEvent
    public void onServerStarting(ServerStartedEvent event) {
        MinecraftServer minecraftServer = event.getServer();
        if (minecraftServer != null) {
            boolean isDedicatedServer = minecraftServer.isDedicatedServer();
            if (isDedicatedServer) {
                server = event.getServer();
                executor = Executors.newScheduledThreadPool(2, r -> new Thread(r, "Buycraft Scheduler Thread"));

                platform = new ForgeBuycraftPlatform(this);

                try {
                    try {
                        Files.createDirectory(baseDirectory);
                    } catch (FileAlreadyExistsException ignored) {
                    }
                    Path configPath = baseDirectory.resolve("config.properties");
                    try {
                        configuration.load(configPath);
                    } catch (NoSuchFileException e) {
                        // Save defaults
                        configuration.fillDefaults();
                        configuration.save(configPath);
                    }
                } catch (IOException e) {
                    getLogger().error("Unable to load configuration! The plugin will disable itself now.", e);
                    return;
                }

                //i18n = configuration.createI18n();
                getLogger().warn("Forcing english translations while we wait on a forge bugfix!");
                httpClient = Setup.okhttp(baseDirectory.resolve("cache").toFile());

                if (configuration.isCheckForUpdates()) {
                    VersionCheck check = new VersionCheck(this, pluginVersion, configuration.getServerKey());
                    try {
                        check.verify();
                    } catch (IOException e) {
                        getLogger().error("Can't check for updates", e);
                    }
                    MinecraftForge.EVENT_BUS.register(check);
                }

                String serverKey = configuration.getServerKey();
                if (serverKey == null || serverKey.equals("INVALID")) {
                    getLogger().info("Looks like this is a fresh setup. Get started by using 'buycraft secret <key>' in the console.");
                } else {
                    getLogger().info("Validating your server key...");
                    BuyCraftAPI client = BuyCraftAPI.create(configuration.getServerKey(), httpClient);
                    try {
                        updateInformation(client);
                    } catch (IOException e) {
                        getLogger().error(String.format("We can't check if your server can connect to Buycraft: %s", e.getMessage()));
                    }
                    apiClient = client;
                }

                placeholderManager.addPlaceholder(new NamePlaceholder());
                placeholderManager.addPlaceholder(new UuidPlaceholder());
                platform.executeAsyncLater(duePlayerFetcher = new DuePlayerFetcher(platform, configuration.isVerbose()), 1, TimeUnit.SECONDS);
                completedCommandsTask = new PostCompletedCommandsTask(platform);
                commandExecutor = new QueuedCommandExecutor(platform, completedCommandsTask);
                scheduledTasks.add(ForgeScheduledTask.Builder.create().withInterval(1).withDelay(1).withTask((Runnable) commandExecutor).build());
                scheduledTasks.add(ForgeScheduledTask.Builder.create().withAsync(true).withInterval(20).withDelay(20).withTask(completedCommandsTask).build());
                playerJoinCheckTask = new PlayerJoinCheckTask(platform);
                scheduledTasks.add(ForgeScheduledTask.Builder.create().withInterval(20).withDelay(20).withTask(playerJoinCheckTask).build());

                if (serverInformation != null) {
                    scheduledTasks.add(ForgeScheduledTask.Builder.create()/*server.isServerInOnlineMode()*/
                            .withAsync(true)
                            .withInterval(20 * 60 * 60 * 24)
                            .withTask(() -> {
                                try {
                                    AnalyticsSend.postServerInformation(httpClient, configuration.getServerKey(), platform, server.usesAuthentication());
                                } catch (IOException e) {
                                    getLogger().warn("Can't send analytics", e);
                                }
                            })
                            .build());
                }
            }
        }
    }

    private static LiteralArgumentBuilder<CommandSourceStack> configureCommand(LiteralArgumentBuilder<CommandSourceStack> command) {
        CouponCmd couponCmd = new CouponCmd(plugin);
        return command
                /*.requires(player -> {
                    try {
                        return player.getPlayerOrException().hasPermissions(2);
                    } catch (CommandSyntaxException e) {
                        return false;
                    }
                })*/
                .then(Commands.literal("coupon")
                        .then(Commands.literal("create")
                                .then(Commands.argument("data", StringArgumentType.greedyString()).executes(couponCmd::create)))
                        .then(Commands.literal("delete")
                                .then(Commands.argument("code", StringArgumentType.word()).executes(couponCmd::delete))))
                .then(Commands.literal("forcecheck").executes(new ForceCheckCmd(plugin)))
                .then(Commands.literal("info").executes(new InfoCmd(plugin)))
                .then(Commands.literal("report").executes(new ReportCmd(plugin)))
                .then(Commands.literal("secret")
                        .then(Commands.argument("secret", StringArgumentType.word()).executes(new SecretCmd(plugin))));
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getPlayer().getServer() != null && event.getPlayer().getServer().isDedicatedServer()) {
            if (apiClient == null) {
                return;
            }

            QueuedPlayer qp = duePlayerFetcher.fetchAndRemoveDuePlayer(event.getPlayer().getName().getString());
            if (qp != null) {
                playerJoinCheckTask.queue(qp);
            }
        }
    }

    // I hate that forge after all these years still hasn't given us a nice scheduler. So here's my poor attempt at
    // DIYing one similar to how bukkits one works, with a nice builder thrown on top.
    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        if (!stopped && server != null && server.isDedicatedServer() && event.phase == TickEvent.Phase.END) {
            scheduledTasks.forEach(task -> {
                if (task.getCurrentDelay() > 0) {
                    task.setCurrentDelay(task.getCurrentDelay() - 1);
                    return;
                }

                if (task.getInterval() > -1 && task.getCurrentIntervalTicks() > 0) {
                    task.setCurrentIntervalTicks(task.getCurrentIntervalTicks() - 1);
                    return;
                }

                if (!task.isAsync()) {
                    try {
                        task.getTask().run();
                    } catch (Exception e) {
                        platform.log(Level.SEVERE, "Error executing scheduled task!", e);
                    }
                } else {
                    executor.submit(task.getTask());
                }

                if (task.getInterval() > -1) {
                    task.setCurrentIntervalTicks(task.getInterval());
                }
            });
            scheduledTasks.removeIf(task -> task.getCurrentDelay() <= 0 && task.getInterval() <= -1);
        }
    }

    @SubscribeEvent
    public void serverStopping(ServerStoppedEvent event) {

        event.getServer().executeBlocking(() -> {
            scheduledTasks.clear();
            if (!executor.isTerminated() || !executor.isShutdown()) {
                executor.shutdownNow();
            }
            try {
                if (!httpClient.cache().isClosed()) {
                    httpClient.cache().flush();
                    httpClient.cache().close();
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            httpClient.dispatcher().cancelAll();
            httpClient.connectionPool().evictAll();
            httpClient = null;
        });

        apiClient = null;
        completedCommandsTask = null;
        commandExecutor = null;
        playerJoinCheckTask = null;
        this.stopped = true;
        plugin = null;
        platform = null;
        LOGGER.info("Unloaded Tebex successfully");


    }

    public Logger getLogger() {
        return LOGGER;
    }

    public final String getPluginVersion() {
        return pluginVersion;
    }

    public MinecraftServer getServer() {
        return server;
    }

    public ScheduledExecutorService getExecutor() {
        return executor;
    }

    public PlaceholderManager getPlaceholderManager() {
        return placeholderManager;
    }

    public BuycraftConfiguration getConfiguration() {
        return configuration;
    }

    public void saveConfiguration() throws IOException {
        Path configPath = getBaseDirectory().resolve("config.properties");
        configuration.save(configPath);
    }

    public Path getBaseDirectory() {
        return baseDirectory;
    }

    public BuyCraftAPI getApiClient() {
        return apiClient;
    }

    public void setApiClient(BuyCraftAPI apiClient) {
        this.apiClient = apiClient;
    }

    public DuePlayerFetcher getDuePlayerFetcher() {
        return duePlayerFetcher;
    }

    public ServerInformation getServerInformation() {
        return serverInformation;
    }

    public void updateInformation(BuyCraftAPI client) throws IOException {
        serverInformation = client.getServerInformation().execute().body();
    }

    public OkHttpClient getHttpClient() {
        return httpClient;
    }

    public IBuycraftPlatform getPlatform() {
        return platform;
    }

    public CommandExecutor getCommandExecutor() {
        return commandExecutor;
    }

    /*
    public BuycraftI18n getI18n() {
        return i18n;
    }
     */

    public PostCompletedCommandsTask getCompletedCommandsTask() {
        return completedCommandsTask;
    }

    public PlayerJoinCheckTask getPlayerJoinCheckTask() {
        return playerJoinCheckTask;
    }
}