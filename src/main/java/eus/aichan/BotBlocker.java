package eus.aichan;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.BannedPlayerEntry;
import net.minecraft.server.command.CommandManager;
import net.minecraft.text.Text;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

public class BotBlocker implements ModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("botblocker");

    final static String COMMAND = "botblocker";
    final static String COMMAND_ENABLE = "enable";
    final static String COMMAND_DISABLE = "disable";
    final static String COMMAND_SET_TIME_LIMIT = "setTimeLimit";

    final static String MESSAGE_DISCONNECT = "Bot detected. If you are a legitimate user, please contact the admin.";
    final static String MESSAGE_DISCONNECT_CONSOLE = "Player %s was banned for disconnecting within %d seconds of joining for the first time - suspected bot.";
    final static String MESSAGE_ENABLED = "BotBlocker enabled.";
    final static String MESSAGE_DISABLED = "BotBlocker disabled.";
    final static String MESSAGE_TIME_LIMIT = "Time limit set to %d seconds.";
	final static String MESSAGE_MOD_LOADED = "BotBlocker mod has loaded with 'time-limit: %d'!";

    final static String PATH_CONFIG = "mods/BotBlocker/config.yml";
    final static String PATH_PLAYERS = "mods/BotBlocker/players.yml";

    private boolean pluginEnabled = true;
    private int timeLimit; // In seconds
    private HashMap<UUID, Long> joinTimes = new HashMap<>();
    private Map<String, Object> config;
    private Map<String, Object> players;
    private Path configPath;
    private Path playersPath;
    private Yaml yaml;

    private boolean isPlayerExempt(UUID playerId) {
        return players.containsKey(playerId.toString()) && (boolean)players.get(playerId.toString());
    }

    @Override
    public void onInitialize() {
		LOGGER.info("BotBlocker mod is loading!");
        DumperOptions options = new DumperOptions();
        options.setPrettyFlow(true);
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        yaml = new Yaml(options);

        configPath = new File(PATH_CONFIG).toPath();
        playersPath = new File(PATH_PLAYERS).toPath();

        loadConfig();
        loadPlayers();
        
        timeLimit = 20; // Default to 20 seconds

		LOGGER.info(String.format(MESSAGE_MOD_LOADED, timeLimit));
        
        // onPlayerJoin: Add the player to the joinTimes map
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            if (!pluginEnabled) return;

            UUID playerId = handler.player.getUuid();
            if(isPlayerExempt(playerId)) return;

            if (!joinTimes.containsKey(playerId)) {
                joinTimes.put(playerId, System.currentTimeMillis());
            }
        });

        // onPlayerQuit: Check if the player is a bot and ban it if it is
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            if (!pluginEnabled) return;

            UUID playerId = handler.player.getUuid();
            if (joinTimes.containsKey(playerId)) {
                long joinTime = joinTimes.get(playerId);
                long timeConnected = (System.currentTimeMillis() - joinTime) / 1000;
                
                if (timeConnected < timeLimit) {
                    String playerName = handler.player.getEntityName();
                    // Ban the player
                    players.put(playerId.toString(), false);
                    server.getPlayerManager().getUserBanList().add(new BannedPlayerEntry(handler.player.getGameProfile(), null, playerName, null, MESSAGE_DISCONNECT));
                    handler.player.networkHandler.disconnect(Text.of(MESSAGE_DISCONNECT));
                    System.out.println(String.format(MESSAGE_DISCONNECT_CONSOLE, playerName, timeLimit));
                } else {
                    // Add the player to players.yml if it is not banned
                    players.put(playerId.toString(), true);
                    joinTimes.remove(playerId);
                }
                savePlayers();
            }
        });

        // onCommand: Register the commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // enable command
            dispatcher.register(CommandManager.literal(COMMAND)
                    .then(CommandManager.literal(COMMAND_ENABLE)
                            .executes(context -> {
                                pluginEnabled = true;
                                context.getSource().sendMessage(Text.of(MESSAGE_ENABLED));
                                config.put("enabled", true);
                                saveConfig();
                                return 1;
                            })));

            // disable command
            dispatcher.register(CommandManager.literal(COMMAND)
                    .then(CommandManager.literal(COMMAND_DISABLE)
                            .executes(context -> {
                                pluginEnabled = false;
                                context.getSource().sendMessage(Text.of(MESSAGE_DISABLED));
                                config.put("enabled", false);
                                saveConfig();
                                return 1;
                            })));

            // setTimeLimit command
            dispatcher.register(CommandManager.literal(COMMAND)
                    .then(CommandManager.literal(COMMAND_SET_TIME_LIMIT)
                            .then(CommandManager.argument("seconds", IntegerArgumentType.integer())
                                    .executes(context -> {
                                        timeLimit = IntegerArgumentType.getInteger(context, "seconds");
                                        context.getSource().sendMessage(Text.of(String.format(MESSAGE_TIME_LIMIT, timeLimit)));
                                        config.put("time-limit", timeLimit);
                                        saveConfig();
                                        return 1;
                                    }))));
        });
    }

    private Map<String, Object> loadConfigs(Path path) {
        if (!Files.exists(path)) {
            try {
                Files.createDirectories(path.getParent());
                Files.createFile(path);
            } catch (IOException e) {
                e.printStackTrace();
            }
            return new HashMap<>();
        }

        try (InputStream input = new FileInputStream(path.toFile())) {
            return yaml.load(input);
        } catch (IOException e) {
            e.printStackTrace();
            return new HashMap<>();
        }
    }

    private void saveConfigs(Map<String, Object> data, Path path) {
        try (Writer writer = new FileWriter(path.toFile())) {
            yaml.dump(data, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void loadPlayers() {
        players = loadConfigs(playersPath);
    }

    private void savePlayers() {
        saveConfigs(players, playersPath);
    }

    private void loadConfig() {
        config = loadConfigs(configPath);
        if(config == null) {
            config = new HashMap<>();
            config.putIfAbsent("enabled", true);
            config.putIfAbsent("time-limit", 20);
            saveConfig();
        }
    }

    private void saveConfig() {
        saveConfigs(config, configPath);
    }
}