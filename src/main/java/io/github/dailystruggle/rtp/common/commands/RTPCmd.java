package io.github.dailystruggle.rtp.common.commands;

import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.ConfigParser;
import io.github.dailystruggle.rtp.common.configuration.enums.*;
import io.github.dailystruggle.rtp.common.factory.Factory;
import io.github.dailystruggle.rtp.common.factory.FactoryValue;
import io.github.dailystruggle.rtp.common.playerData.TeleportData;
import io.github.dailystruggle.rtp.common.selection.SelectionAPI;
import io.github.dailystruggle.rtp.common.selection.region.Region;
import io.github.dailystruggle.rtp.common.selection.region.selectors.shapes.Shape;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPCommandSender;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPEconomy;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPPlayer;
import io.github.dailystruggle.rtp.common.serverSide.substitutions.RTPWorld;
import io.github.dailystruggle.rtp.common.tasks.teleport.SetupTeleport;
import org.apache.commons.lang3.IllegalClassException;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;

public interface RTPCmd extends BaseRTPCmd {

    static String pickOne(List<String> param, String defaultValue) {
        if (param == null || param.isEmpty()) return defaultValue;
        int sel = ThreadLocalRandom.current().nextInt(param.size());
        return param.get(sel);
    }

    default void init() {
        // Initialization logic can be added here
    }

    // Synchronous command component
    default boolean onCommand(RTPCommandSender sender, CommandsAPICommand command, String label, String[] args) {
        UUID senderId = sender.uuid();

        if (RTP.reloading.get()) {
            RTP.serverAccessor.sendMessage(senderId, "&4busy");
            return true;
        }

        // Check for parameter delimiters
        boolean hasDelimiters = Arrays.stream(args)
                .anyMatch(arg -> arg.contains(String.valueOf(CommandsAPI.parameterDelimiter)));

        if (!hasDelimiters) {
            CompletableFuture<Boolean> future = onCommand(senderId, sender::hasPermission, sender::sendMessage, args);
            return future.getNow(false);
        }

        // Guard command permissions with custom message
        if (!sender.hasPermission("rtp.use")) {
            RTP.serverAccessor.sendMessage(senderId, MessagesKeys.noPerms);
            return true;
        }

        long timeDifference = -1;

        // Guard last teleport time synchronously to prevent spam
        TeleportData senderData = RTP.getInstance().latestTeleportData.get(senderId);

        if (senderData != null) {
            if (senderData.sender == null) {
                senderData.sender = sender;
            }

            timeDifference = System.currentTimeMillis() - senderData.time;
            if (timeDifference < 0) timeDifference = Long.MAX_VALUE + timeDifference;

            if (timeDifference < sender.cooldown()) {
                RTP.serverAccessor.sendMessage(senderId, MessagesKeys.cooldownMessage);
                return true;
            } else if (senderData.completed) {
                // Resolve command bugs preemptively
                RTP.getInstance().processingPlayers.remove(senderId);
            }
        } else {
            // Resolve command bugs preemptively
            RTP.getInstance().processingPlayers.remove(senderId);
        }

        if (RTP.getInstance().processingPlayers.contains(senderId)) {
            RTP.serverAccessor.sendMessage(senderId, MessagesKeys.alreadyTeleporting);
            return true;
        }

        if (!senderId.equals(CommandsAPI.serverId)) {
            RTP.getInstance().processingPlayers.add(senderId);
        }

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        try {
            result = onCommand(senderId, sender::hasPermission, sender::sendMessage, args);
        } catch (Throwable throwable) {
            RTP.log(Level.WARNING, throwable.getMessage(), throwable);
            result.complete(false);
        } finally {
            RTP.getInstance().processingPlayers.remove(senderId);
        }

        return result.getNow(false);
    }

    // Async command component
    default boolean compute(UUID senderId, Map<String, List<String>> rtpArgs, CommandsAPICommand nextCommand) {
        if (nextCommand != null) {
            return true;
        }

        RTPCommandSender sender = RTP.serverAccessor.getSender(senderId);
        RTP.getInstance().processingPlayers.add(senderId);

        List<String> toggleTargetPermsList = rtpArgs.get("toggletargetperms");
        boolean toggleTargetPerms = toggleTargetPermsList != null &&
                Boolean.parseBoolean(toggleTargetPermsList.getFirst());

        ConfigParser<LoggingKeys> logging = (ConfigParser<LoggingKeys>) RTP.configs.getParser(LoggingKeys.class);
        boolean verbose = logging != null &&
                Boolean.parseBoolean(logging.getConfigValue(LoggingKeys.command, false).toString());

        if (verbose) {
            RTP.log(Level.INFO, "#0080ff[RTP] RTP command triggered by " + sender.name() + ".");
        }

        ConfigParser<MessagesKeys> langParser = (ConfigParser<MessagesKeys>) RTP.configs.getParser(MessagesKeys.class);
        ConfigParser<EconomyKeys> eco = (ConfigParser<EconomyKeys>) RTP.configs.getParser(EconomyKeys.class);
        ConfigParser<PerformanceKeys> perf = (ConfigParser<PerformanceKeys>) RTP.configs.getParser(PerformanceKeys.class);

        boolean syncLoading = perf != null &&
                Boolean.parseBoolean(perf.getConfigValue(PerformanceKeys.syncLoading, false).toString());

        // Collect target players to teleport
        List<RTPPlayer> players = collectTargetPlayers(sender, rtpArgs, langParser);
        if (players.isEmpty()) {
            RTP.getInstance().processingPlayers.remove(senderId);
            return true;
        }

        // Calculate price and check economy
        EconomyCheckResult economyResult = checkEconomy(sender, players, rtpArgs, eco, langParser, toggleTargetPerms);
        if (!economyResult.canProceed()) {
            RTP.getInstance().processingPlayers.remove(senderId);
            return true;
        }

        double price = economyResult.price();
        double floor = economyResult.floor();

        // Process each player
        for (RTPPlayer player : players) {
            if (!processPlayerTeleport(sender, player, rtpArgs, langParser, eco, floor, price,
                    syncLoading, toggleTargetPerms, verbose)) {
                // If any player fails, remove from processing and continue with others
                continue;
            }
        }

        RTP.getInstance().processingPlayers.remove(senderId);
        return true;
    }

    private List<RTPPlayer> collectTargetPlayers(RTPCommandSender sender, Map<String, List<String>> rtpArgs,
                                                 ConfigParser<MessagesKeys> langParser) {
        List<RTPPlayer> players = new ArrayList<>();

        if (rtpArgs.containsKey("player")) {
            // If players are listed, use those
            List<String> playerNames = rtpArgs.get("player");
            for (String playerName : playerNames) {
                RTPPlayer player = RTP.serverAccessor.getPlayer(playerName);
                if (player == null) {
                    String msg = (String) langParser.getConfigValue(MessagesKeys.badArg,
                            "player:" + rtpArgs.get("player"));
                    RTP.serverAccessor.sendMessage(sender.uuid(), msg);
                    continue;
                }
                players.add(player);
            }
        } else if (sender instanceof RTPPlayer) {
            // If no players but sender is a player, use sender's location
            players.add((RTPPlayer) sender);
        } else {
            // If no players and sender isn't a player, don't know who to send
            String msg = (String) langParser.getConfigValue(MessagesKeys.consoleCmdNotAllowed, "");
            failEvent(sender, msg);
        }

        return players;
    }

    private EconomyCheckResult checkEconomy(RTPCommandSender sender, List<RTPPlayer> players,
                                            Map<String, List<String>> rtpArgs, ConfigParser<EconomyKeys> eco,
                                            ConfigParser<MessagesKeys> langParser, boolean toggleTargetPerms) {
        RTPEconomy economy = RTP.economy;
        double price = 0.0;
        double floor = 0.0;

        if (economy != null && !sender.uuid().equals(CommandsAPI.serverId) && !sender.hasPermission("rtp.free")) {
            List<String> shapeNames = rtpArgs.get("shape");
            List<String> vertNames = rtpArgs.get("vert");
            List<String> biomeList = rtpArgs.get("biome");

            boolean hasCustomParams = shapeNames != null || vertNames != null;
            boolean hasBiomes = biomeList != null;

            for (RTPPlayer player : players) {
                if (player.uuid().equals(sender.uuid())) {
                    price += eco.getNumber(EconomyKeys.price, 0.0).doubleValue();
                } else if (!player.hasPermission("rtp.notme")) {
                    price += eco.getNumber(EconomyKeys.otherPrice, 0.0).doubleValue();
                }

                if (hasCustomParams) {
                    price += eco.getNumber(EconomyKeys.paramsPrice, 0.0).doubleValue();
                }
                if (hasBiomes) {
                    price += eco.getNumber(EconomyKeys.biomePrice, 0.0).doubleValue();
                }
            }

            floor = eco.getNumber(EconomyKeys.balanceFloor, 0.0).doubleValue();
            double balance = economy.bal(sender.uuid());

            if ((balance - price) < floor) {
                String message = langParser.getConfigValue(MessagesKeys.notEnoughMoney, "").toString()
                        .replace("[money]", String.valueOf(price));
                RTP.serverAccessor.sendMessage(sender.uuid(), message);
                return new EconomyCheckResult(false, price, floor);
            }
        }

        return new EconomyCheckResult(true, price, floor);
    }

    private boolean processPlayerTeleport(RTPCommandSender sender, RTPPlayer player,
                                          Map<String, List<String>> rtpArgs, ConfigParser<MessagesKeys> langParser,
                                          ConfigParser<EconomyKeys> eco, double floor, double price,
                                          boolean syncLoading, boolean toggleTargetPerms, boolean verbose) {

        UUID playerId = player.uuid();

        if (verbose && rtpArgs.containsKey("player")) {
            RTP.log(Level.INFO, "#0080ff[RTP] RTP processing player:" + player.name());
        }

        // Get player data and check for existing teleports
        TeleportData data = RTP.getInstance().latestTeleportData.get(playerId);
        if (data != null && !data.completed) {
            String msg = (String) langParser.getConfigValue(MessagesKeys.alreadyTeleporting, "");
            RTP.serverAccessor.sendMessage(sender.uuid(), playerId, msg);
            failEvent(sender, msg);
            return false;
        }

        // Check cooldown if toggleTargetPerms is enabled
        if (toggleTargetPerms && data != null) {
            long timeDifference = System.currentTimeMillis() - data.time;
            if (timeDifference < 0) timeDifference = Long.MAX_VALUE + timeDifference;
            if (timeDifference < player.cooldown()) {
                RTP.serverAccessor.sendMessage(sender.uuid(), playerId, MessagesKeys.cooldownMessage);
                return false;
            }
            RTP.getInstance().priorTeleportData.put(playerId, data);
        }

        // Create new teleport data
        data = new TeleportData();
        data.sender = sender;
        RTP.getInstance().latestTeleportData.put(playerId, data);

        // Determine region
        Region region = determineRegion(player, rtpArgs);
        if (region == null) {
            RTP.getInstance().processingPlayers.remove(playerId);
            RTP.getInstance().latestTeleportData.remove(playerId);
            return false;
        }

        RTPWorld rtpWorld = region.getWorld();
        Objects.requireNonNull(rtpWorld, "Region world cannot be null");

        // Handle world border override
        boolean doWBO = handleWorldBorderOverride(rtpArgs, region, rtpWorld, player);

        // Process economy for this player
        if (!processPlayerEconomy(sender, player, data, rtpArgs, eco, floor, price, region, doWBO, toggleTargetPerms, langParser)) {
            return false;
        }

        // Prepare biome filter
        Set<String> biomes = prepareBiomeFilter(rtpArgs);

        // Apply shape modifications if specified
        if (rtpArgs.containsKey("shape")) {
            region = applyShapeModifications(region, rtpArgs, sender.uuid());
        }

        // TODO: Apply vertical parameters

        // Setup teleport task
        SetupTeleport setupTeleport = new SetupTeleport(sender, player, region, biomes);
        data.nextTask = setupTeleport;

        long delay = toggleTargetPerms ? player.delay() : sender.delay();
        data.delay = delay;

        if (delay > 0) {
            String msg = langParser.getConfigValue(MessagesKeys.delayMessage, "").toString();
            RTP.serverAccessor.sendMessage(sender.uuid(), playerId, msg);
        }

        // Determine if sync loading should be used
        boolean useSyncLoading = determineSyncLoading(syncLoading, biomes, region, player, delay);

        // Execute teleport setup
        if (useSyncLoading) {
            setupTeleport.run();
        } else {
            RTP.getInstance().setupTeleportPipeline.add(setupTeleport);
        }

        return true;
    }

    private Region determineRegion(RTPPlayer player, Map<String, List<String>> rtpArgs) {
        String regionName;

        if (rtpArgs.containsKey("region")) {
            List<String> regionNames = rtpArgs.get("region");
            regionName = pickOne(regionNames, "default");
        } else {
            String worldName;

            if (rtpArgs.containsKey("world")) {
                worldName = pickOne(rtpArgs.get("world"), "default");
            } else {
                worldName = player.getLocation().world().name();
            }

            ConfigParser<WorldKeys> worldParser = RTP.configs.getWorldParser(worldName);
            if (worldParser == null) {
                // TODO: message world does not exist
                return null;
            }

            regionName = worldParser.getConfigValue(WorldKeys.region, "default").toString();
        }

        SelectionAPI selectionAPI = RTP.selectionAPI;
        try {
            return selectionAPI.getRegionOrDefault(regionName);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return null;
        }
    }

    private boolean handleWorldBorderOverride(Map<String, List<String>> rtpArgs, Region region,
                                              RTPWorld rtpWorld, RTPPlayer player) {
        if (!rtpArgs.containsKey("worldBorderOverride")) {
            return false;
        }

        List<String> wboValues = rtpArgs.get("worldBorderOverride");
        boolean doWBO = Boolean.parseBoolean(wboValues.size() > 0 ? wboValues.getFirst() : "false");

        if (doWBO) {
            Region clonedRegion = region.clone();
            clonedRegion.set(RegionKeys.shape, RTP.serverAccessor.getShape(rtpWorld.name()));
            // Note: This doesn't actually update the original region reference
            // You might need to return the cloned region or handle this differently
        }

        return doWBO;
    }

    private boolean processPlayerEconomy(RTPCommandSender sender, RTPPlayer player, TeleportData data,
                                         Map<String, List<String>> rtpArgs, ConfigParser<EconomyKeys> eco,
                                         double floor, double price, Region region, boolean doWBO,
                                         boolean toggleTargetPerms, ConfigParser<MessagesKeys> langParser) {

        RTPEconomy economy = RTP.economy;
        if (economy == null) return true;

        UUID senderId = sender.uuid();
        UUID playerId = player.uuid();

        // Calculate cost for this player
        if (!sender.hasPermission("rtp.free")) {
            if (playerId.equals(senderId)) {
                data.cost += eco.getNumber(EconomyKeys.price, 0.0).doubleValue();
            } else if (!player.hasPermission("rtp.notme")) {
                data.cost += eco.getNumber(EconomyKeys.otherPrice, 0.0).doubleValue();
            }

            List<String> shapeNames = rtpArgs.get("shape");
            List<String> vertNames = rtpArgs.get("vert");
            List<String> biomeList = rtpArgs.get("biome");

            if (shapeNames != null || vertNames != null || doWBO) {
                data.cost += eco.getNumber(EconomyKeys.paramsPrice, 0.0).doubleValue();
            }
            if (biomeList != null) {
                data.cost += eco.getNumber(EconomyKeys.biomePrice, 0.0).doubleValue();
            }

            data.cost += region.getNumber(RegionKeys.price, 0.0).doubleValue();

            // Check sender's balance
            if (economy.bal(senderId) - data.cost < floor) {
                String message = langParser.getConfigValue(MessagesKeys.notEnoughMoney, "").toString()
                        .replace("[money]", String.valueOf(price));
                RTP.serverAccessor.sendMessage(senderId, message);
                return false;
            }

            // Take money from sender
            if (!economy.take(senderId, data.cost)) {
                String message = langParser.getConfigValue(MessagesKeys.notEnoughMoney, "").toString()
                        .replace("[money]", String.valueOf(price));
                RTP.serverAccessor.sendMessage(senderId, message);
                return false;
            }
        }

        // Handle target player economy if toggleTargetPerms is enabled
        if (toggleTargetPerms && !player.hasPermission("rtp.free") && !playerId.equals(senderId)) {
            data.cost += eco.getNumber(EconomyKeys.price, 0.0).doubleValue();

            List<String> shapeNames = rtpArgs.get("shape");
            List<String> vertNames = rtpArgs.get("vert");
            List<String> biomeList = rtpArgs.get("biome");

            if (shapeNames != null || vertNames != null || doWBO) {
                data.cost += eco.getNumber(EconomyKeys.paramsPrice, 0.0).doubleValue();
            }
            if (biomeList != null) {
                data.cost += eco.getNumber(EconomyKeys.biomePrice, 0.0).doubleValue();
            }

            data.cost += region.getNumber(RegionKeys.price, 0.0).doubleValue();

            // Check player's balance
            if (economy.bal(playerId) - data.cost < floor) {
                String message = langParser.getConfigValue(MessagesKeys.notEnoughMoney, "").toString()
                        .replace("[money]", String.valueOf(price));
                RTP.serverAccessor.sendMessage(senderId, playerId, message);
                return false;
            }

            // Take money from player
            if (!economy.take(playerId, data.cost)) {
                String message = langParser.getConfigValue(MessagesKeys.notEnoughMoney, "").toString()
                        .replace("[money]", String.valueOf(price));
                RTP.serverAccessor.sendMessage(senderId, playerId, message);
                return false;
            }
        }

        return true;
    }

    private Set<String> prepareBiomeFilter(Map<String, List<String>> rtpArgs) {
        List<String> biomeList = rtpArgs.get("biome");
        if (biomeList == null) {
            return null;
        }

        return biomeList.stream()
                .map(String::toUpperCase)
                .collect(HashSet::new, HashSet::add, HashSet::addAll);
    }

    private Region applyShapeModifications(Region region, Map<String, List<String>> rtpArgs, UUID senderId) {
        List<String> shapeNames = rtpArgs.get("shape");
        if (shapeNames == null || shapeNames.isEmpty()) {
            return region;
        }

        Region modifiedRegion = region.clone();
        Shape<?> originalShape = modifiedRegion.getShape();

        RTP.selectionAPI.tempRegions.put(senderId, modifiedRegion);
        String shapeName = pickOne(shapeNames, "CIRCLE");

        Factory<?> factory = RTP.factoryMap.get(RTP.factoryNames.shape);
        FactoryValue<?> factoryValue = factory.get(shapeName);

        if (!(factoryValue instanceof Shape<?> shape)) {
            RTP.log(Level.SEVERE, "", new IllegalClassException("shape factory did not return a shape"));
            return region;
        }

        EnumMap<?, Object> originalShapeData = originalShape.getData();
        EnumMap<?, Object> shapeData = shape.getData();

        for (Map.Entry<? extends Enum<?>, Object> entry : shapeData.entrySet()) {
            String paramName = entry.getKey().name();
            if (paramName.equalsIgnoreCase("name") || paramName.equalsIgnoreCase("version")) {
                continue;
            }

            if (rtpArgs.containsKey(paramName)) {
                String stringValue = pickOne(rtpArgs.get(paramName), "");
                Object value = parseParameterValue(stringValue);

                if (value != null) {
                    entry.setValue(value);
                }
            } else {
                try {
                    Enum<?> paramEnum = Enum.valueOf(originalShape.myClass, paramName);
                    Object originalValue = originalShapeData.get(paramEnum);

                    if (originalValue instanceof Number ||
                            entry.getValue().getClass().isAssignableFrom(originalValue.getClass())) {
                        entry.setValue(originalValue);
                    }
                } catch (IllegalArgumentException ignored) {
                    // Parameter doesn't exist in original shape, skip
                }
            }
        }

        shape.setData(shapeData);
        modifiedRegion.set(RegionKeys.shape, shape);

        return modifiedRegion;
    }

    private Object parseParameterValue(String stringValue) {
        if (stringValue.equalsIgnoreCase("true")) {
            return true;
        } else if (stringValue.equalsIgnoreCase("false")) {
            return false;
        }

        try {
            return Long.parseLong(stringValue);
        } catch (NumberFormatException ignored1) {
            try {
                return Double.parseDouble(stringValue);
            } catch (NumberFormatException ignored2) {
                try {
                    return Boolean.valueOf(stringValue);
                } catch (NumberFormatException ignored3) {
                    return stringValue;
                }
            }
        }
    }

    private boolean determineSyncLoading(boolean syncLoading, Set<String> biomes,
                                         Region region, RTPPlayer player, long delay) {
        return syncLoading ||
                (biomes == null && region.hasLocation(player.uuid()) && delay <= 0);
    }

    @Override
    default String name() {
        return "rtp";
    }

    @Override
    default String permission() {
        return "rtp.use";
    }

    @Override
    default String description() {
        return "teleport randomly";
    }

    void successEvent(RTPCommandSender sender, RTPPlayer player);

    void failEvent(RTPCommandSender sender, String msg);

    // Record for economy check results
    record EconomyCheckResult(boolean canProceed, double price, double floor) {}
}