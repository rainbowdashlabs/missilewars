/*
 * This file is part of MissileWars (https://github.com/Butzlabben/missilewars).
 * Copyright (c) 2018-2021 Daniel Nägele.
 *
 * MissileWars is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * MissileWars is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with MissileWars.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.butzlabben.missilewars.game;

import de.butzlabben.missilewars.Logger;
import de.butzlabben.missilewars.MissileWars;
import de.butzlabben.missilewars.configuration.Config;
import de.butzlabben.missilewars.configuration.Messages;
import de.butzlabben.missilewars.configuration.arena.Arena;
import de.butzlabben.missilewars.configuration.lobby.Lobby;
import de.butzlabben.missilewars.event.GameStartEvent;
import de.butzlabben.missilewars.event.GameStopEvent;
import de.butzlabben.missilewars.game.enums.GameResult;
import de.butzlabben.missilewars.game.enums.GameState;
import de.butzlabben.missilewars.game.enums.MapChooseProcedure;
import de.butzlabben.missilewars.game.enums.TeamType;
import de.butzlabben.missilewars.game.equipment.EquipmentManager;
import de.butzlabben.missilewars.game.misc.MotdManager;
import de.butzlabben.missilewars.game.misc.ScoreboardManager;
import de.butzlabben.missilewars.game.misc.TeamSpawnProtection;
import de.butzlabben.missilewars.game.schematics.SchematicFacing;
import de.butzlabben.missilewars.game.schematics.objects.Missile;
import de.butzlabben.missilewars.game.schematics.objects.Shield;
import de.butzlabben.missilewars.game.signs.MWSign;
import de.butzlabben.missilewars.game.stats.FightStats;
import de.butzlabben.missilewars.game.timer.EndTimer;
import de.butzlabben.missilewars.game.timer.GameTimer;
import de.butzlabben.missilewars.game.timer.LobbyTimer;
import de.butzlabben.missilewars.game.timer.TaskManager;
import de.butzlabben.missilewars.listener.game.EndListener;
import de.butzlabben.missilewars.listener.game.GameBoundListener;
import de.butzlabben.missilewars.listener.game.GameListener;
import de.butzlabben.missilewars.listener.game.LobbyListener;
import de.butzlabben.missilewars.player.MWPlayer;
import de.butzlabben.missilewars.util.geometry.GameArea;
import de.butzlabben.missilewars.util.geometry.Geometry;
import de.butzlabben.missilewars.util.serialization.Serializer;
import lombok.Getter;
import lombok.ToString;
import org.bukkit.*;
import org.bukkit.entity.Fireball;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.function.Consumer;

/**
 * @author Butzlabben
 * @since 01.01.2018
 */

@Getter
@ToString(of = {"gameWorld", "players", "lobby", "arena", "state"})
public class Game {

    private static final Map<String, Integer> cycles = new HashMap<>();
    private static int fights = 0;
    private final Map<UUID, MWPlayer> players = new HashMap<>();
    private final MapVoting mapVoting = new MapVoting(this);
    private final Lobby lobby;
    private final Map<UUID, BukkitTask> playerTasks = new HashMap<>();
    private GameState state = GameState.LOBBY;
    private TeamManager teamManager;
    private boolean ready = false;
    private boolean restart = false;
    private GameWorld gameWorld;
    private GameArea gameArea;
    private GameArea innerGameArea;
    private long timestart;
    private Arena arena;
    private ScoreboardManager scoreboardManager;
    private GameJoinManager gameJoinManager;
    private GameLeaveManager gameLeaveManager;
    private GameBoundListener listener;
    private EquipmentManager equipmentManager;
    private TaskManager taskManager;
    private int remainingGameDuration;
    
    public Game(Lobby lobby) {
        Logger.BOOT.log("Loading lobby \"" + lobby.getName() + "\".");
        this.lobby = lobby;

        if (lobby.getBukkitWorld() == null) {
            Logger.ERROR.log("Lobby world \"" + lobby.getName() + "\" must not be null");
            return;
        }

        try {
            Serializer.setWorldAtAllLocations(lobby, lobby.getBukkitWorld());
        } catch (Exception exception) {
            Logger.ERROR.log("Could not inject world object at lobby \"" + lobby.getName() + "\".");
            exception.printStackTrace();
            return;
        }

        if (lobby.getPossibleArenas().isEmpty()) {
            Logger.ERROR.log("At least one valid arena must be set at lobby \"" + lobby.getName() + "\".");
            return;
        }

        if (lobby.getPossibleArenas().stream().noneMatch(Arenas::existsArena)) {
            Logger.ERROR.log("None of the specified arenas match a real arena for the lobby \"" + lobby.getName() + "\".");
            return;
        }
        
        teamManager = new TeamManager(this);
        
        Logger.DEBUG.log("Registering, teleporting, etc. all players");

        updateMOTD();

        Logger.DEBUG.log("Start timer");

        taskManager = new TaskManager(this);
        taskManager.stopTimer();
        updateGameListener(new LobbyListener(this));
        taskManager.setTimer(new LobbyTimer(this, lobby.getLobbyTime()));
        taskManager.runTimer(0, 20);
        state = GameState.LOBBY;

        Bukkit.getScheduler().runTaskLater(MissileWars.getInstance(), () -> applyForAllPlayers(player -> gameJoinManager.runTeleportEventForPlayer(player)), 2);

        if (Config.isSetup()) {
            Logger.WARN.log("Did not fully initialize lobby \"" + lobby.getName() + "\" as the plugin is in setup mode");
            return;
        }

        scoreboardManager = new ScoreboardManager(this);
        gameJoinManager = new GameJoinManager(this);
        gameLeaveManager = new GameLeaveManager(this);
        
        // choose the game arena
        if (lobby.getMapChooseProcedure() == MapChooseProcedure.FIRST) {
            setArena(lobby.getArenas().get(0));
            prepareGame();

        } else if (lobby.getMapChooseProcedure() == MapChooseProcedure.MAPCYCLE) {
            final int lastMapIndex = cycles.getOrDefault(lobby.getName(), -1);
            List<Arena> arenas = lobby.getArenas();
            int index = lastMapIndex >= arenas.size() - 1 ? 0 : lastMapIndex + 1;
            cycles.put(lobby.getName(), index);
            setArena(arenas.get(index));
            prepareGame();

        } else if (lobby.getMapChooseProcedure() == MapChooseProcedure.MAPVOTING) {
            if (mapVoting.onlyOneArenaFound()) {
                setArena(lobby.getArenas().get(0));
                Logger.WARN.log("Only one arena was found for the lobby \"" + lobby.getName() + "\". The configured map voting was skipped.");
                prepareGame();
            } else {
                mapVoting.startVote();
                updateGameInfo();
            }
        }

    }

    /**
     * This method performs the final preparations for the game start.
     * <p>
     * It is necessary that the arena - even in the case of a map vote - is
     * now already defined.
     */
    public void prepareGame() {
        if (this.arena == null) {
            throw new IllegalStateException("The arena is not yet set");
        }
        
        updateGameInfo();

        equipmentManager = new EquipmentManager(this);
        equipmentManager.createGameItems();

        Logger.DEBUG.log("Making game ready");
        ++fights;
        checkFightRestart();

        FightStats.checkTables();
        Logger.DEBUG.log("Fights: " + fights);

        ready = true;
    }

    private void checkFightRestart() {
        if (Config.getFightRestart() <= 0) return;

        if (fights >= Config.getFightRestart()) restart = true;
    }

    private void updateGameListener(GameBoundListener newListener) {
        if (listener != null) HandlerList.unregisterAll(listener);

        Bukkit.getPluginManager().registerEvents(newListener, MissileWars.getInstance());
        this.listener = newListener;
    }

    private void updateMOTD() {
        if (!Config.isMultipleLobbies()) {
            MotdManager.getInstance().updateMOTD(this);
        }
    }

    public void startGame() {
        if (Config.isSetup()) {
            Logger.WARN.log("Did not start game. Setup mode is still enabled");
            return;
        }

        World world = gameWorld.getWorld();

        if (world == null) {
            Logger.ERROR.log("Could not start game in arena \"" + arena.getName() + "\". World is null");
            return;
        }

        taskManager.stopTimer();
        updateGameListener(new GameListener(this));
        taskManager.setTimer(new GameTimer(this));
        taskManager.runTimer(5, 20);
        state = GameState.INGAME;

        timestart = System.currentTimeMillis();

        applyForAllPlayers(player -> gameJoinManager.startForPlayer(player, true));

        updateMOTD();

        Bukkit.getPluginManager().callEvent(new GameStartEvent(this));
    }

    public void stopGame() {
        if (Config.isSetup()) return;

        Logger.DEBUG.log("Stopping");

        for (BukkitTask bt : playerTasks.values()) {
            bt.cancel();
        }

        Logger.DEBUG.log("Stopping for players");
        for (Player player : gameWorld.getWorld().getPlayers()) {

            Logger.DEBUG.log("Stopping for: " + player.getName());
            player.setGameMode(GameMode.SPECTATOR);
            teleportToArenaSpectatorSpawn(player);

        }

        // Save the remaining game duration.
        remainingGameDuration = taskManager.getTimer().getSeconds();

        taskManager.stopTimer();
        updateGameListener(new EndListener(this));
        taskManager.setTimer(new EndTimer(this));
        taskManager.runTimer(5, 20);
        state = GameState.END;

        updateMOTD();

        if (arena.isSaveStatistics()) {
            FightStats stats = new FightStats(this);
            stats.insert();
        }

        Logger.DEBUG.log("Stopped completely");
        Bukkit.getPluginManager().callEvent(new GameStopEvent(this));
    }

    public void reset() {
        if (Config.isSetup()) return;

        if (restart) {
            Bukkit.getServer().spigot().restart();
            return;
        }

        GameManager.getInstance().restartGame(lobby, false);
    }

    public void appendRestart() {
        restart = true;
    }

    public void disableGameOnServerStop() {

        for (MWPlayer mwPlayer : players.values()) {
            teleportToFallbackSpawn(mwPlayer.getPlayer());
        }

        if (gameWorld != null) gameWorld.unload();
    }
    
    public void resetGame() {
        // Teleporting players; the event listener will handle the teleport event
        applyForAllPlayers(this::teleportToAfterGameSpawn);

        // Deactivation of all event handlers
        HandlerList.unregisterAll(listener);
        taskManager.stopTimer();

        if (gameWorld != null) {
            gameWorld.unload();
            gameWorld.delete();
        }

        if (scoreboardManager != null) {
            scoreboardManager.removeScoreboard();
        }
    }

    /**
     * This method checks if the location is inside in the Lobby-Area.
     *
     * @param location (Location) the location to be checked
     *
     * @return true, if it's in the Lobby-Area
     */
    public boolean isInLobbyArea(Location location) {
        return Geometry.isInsideIn(location, lobby.getArea());
    }

    /**
     * This method checks if the location is inside in the Game-Area.
     *
     * @param location (Location) the location to be checked
     *
     * @return true, if it's in the Game-Area
     */
    public boolean isInGameArea(Location location) {
        return Geometry.isInsideIn(location, gameArea);
    }

    /**
     * This method checks if the location is inside in the Inner Game-Area.
     * It's the arena from the Team 1 spawn position to the Team 2 spawn
     * position ("length") with the same "width" of the (major) Game-Area.
     *
     * @param location (Location) the location to be checked
     *
     * @return true, if it's in the Inner Game-Area
     */
    public boolean isInInnerGameArea(Location location) {
        return Geometry.isInsideIn(location, innerGameArea);
    }

    /**
     * This method checks if the location is in the game world.
     *
     * @param location (Location) the location to be checked
     *
     * @return true, if it's in the game world
     */
    public boolean isInGameWorld(Location location) {
        // Is possible during the map voting phase:
        if (gameArea == null) return false;

        return Geometry.isInWorld(location, gameArea.getWorld());
    }

    /**
     * This (shortcut) method checks if the location is inside in the
     * Lobby-Area or inside in the game world.
     *
     * @param location (Location) the location to be checked
     *
     * @return true, if the statement is correct
     */
    public boolean isIn(Location location) {
        return isInLobbyArea(location) || isInGameWorld(location);
    }
    
    public MWPlayer getPlayer(Player player) {
        return players.get(player.getUniqueId());
    }

    /**
     * This method finally removes the player from the game player array. Besides former
     * team members, it also affects spectators.
     */
    public void removePlayer(MWPlayer mwPlayer) {
        players.remove(mwPlayer.getUuid());
    }

    public void broadcast(String message) {
        for (MWPlayer mwPlayer : players.values()) {
            Player player = mwPlayer.getPlayer();
            if (player != null && player.isOnline()) player.sendMessage(message);
        }
    }

    /**
     * This method sets the player attributes (game mode, level, enchantments, ...).
     *
     * @param player the target player
     */
    public void setPlayerAttributes(Player player) {

        player.setGameMode(GameMode.SURVIVAL);
        player.setLevel(0);
        player.setFireTicks(0);

    }

    /**
     * This method respawns the player after a short time.
     *
     * @param mwPlayer the target MissileWars player
     */
    public void autoRespawnPlayer(MWPlayer mwPlayer) {
        Bukkit.getScheduler().runTaskLater(MissileWars.getInstance(), () -> {
            TeamSpawnProtection.regenerateSpawn(mwPlayer.getTeam());
            mwPlayer.getPlayer().spigot().respawn();
            }, 20L);
    }

    /**
     * This method spawns the missile for the player.
     *
     * @param player the executing player
     * @param itemStack the spawn egg
     */
    public void spawnMissile(Player player, ItemStack itemStack) {

        // Are missiles only allowed to spawn inside the arena, between the two arena spawn points?
        boolean isOnlyBetweenSpawnPlaceable = this.arena.getMissileConfiguration().isOnlyBetweenSpawnPlaceable();
        if (isOnlyBetweenSpawnPlaceable) {
            if (!isInInnerGameArea(player.getLocation())) {
                player.sendMessage(Messages.getMessage(true, Messages.MessageEnum.ARENA_MISSILE_PLACE_DENY));
                return;
            }
        }
        
        ItemMeta itemMeta = itemStack.getItemMeta();
        if (itemMeta == null) return;
        
        Missile missile = (Missile) this.arena.getMissileConfiguration().getSchematicFromDisplayName(itemMeta.getDisplayName());
        if (missile == null) {
            player.sendMessage(Messages.getMessage(true, Messages.MessageEnum.COMMAND_INVALID_MISSILE)
                    .replace("%input%", itemMeta.getDisplayName()));
            return;
        }
        
        itemStack.setAmount(itemStack.getAmount() - 1);
        player.setItemInHand(itemStack);
        missile.paste(this, player, SchematicFacing.getFacingPlayer(player, this.arena.getMissileConfiguration()));
    }

    /**
     * This method spawns the shield after his flight route.
     *
     * @param player the executing player
     * @param ball the snowball
     */
    public void spawnShield(Player player, Snowball ball) {
        
        ItemMeta itemMeta = ball.getItem().getItemMeta();
        if (itemMeta == null) return;

        Shield shield = (Shield) this.arena.getShieldConfiguration().getSchematicFromDisplayName(itemMeta.getDisplayName());
        if (shield == null) {
            player.sendMessage(Messages.getMessage(true, Messages.MessageEnum.COMMAND_INVALID_SHIELD)
                    .replace("%input%", itemMeta.getDisplayName()));
            return;
        }
        
        shield.paste(ball);
    }

    /**
     * This method spawns the fireball for the player.
     *
     * @param player the executing player
     */
    public void spawnFireball(Player player, ItemStack itemStack) {
        int amount = itemStack.getAmount();
        itemStack.setAmount(amount - 1);

        Fireball fb = player.launchProjectile(Fireball.class);
        fb.setDirection(player.getLocation().getDirection().multiply(2.5D));
        player.playSound(fb.getLocation(), Sound.BLOCK_ANVIL_LAND, 100.0F, 2.0F);
        player.playSound(fb.getLocation(), Sound.ITEM_FLINTANDSTEEL_USE, 100.0F, 1.0F);
        fb.setYield(3F);
        fb.setIsIncendiary(true);
        fb.setBounce(false);
    }

    public void setArena(Arena arena) {
        if (this.arena != null) {
            throw new IllegalStateException("Arena already set");
        }

        arena.getMissileConfiguration().check();
        arena.getShieldConfiguration().check();

        this.arena = arena.clone();
        gameWorld = new GameWorld(this, arena.getTemplateWorld());
        gameWorld.load();
        gameArea = new GameArea(gameWorld.getWorld(), arena.getAreaConfig());

        try {
            Serializer.setWorldAtAllLocations(this.arena, gameWorld.getWorld());
            teamManager.getTeam1().setSpawn(this.arena.getTeam1Spawn());
            teamManager.getTeam2().setSpawn(this.arena.getTeam2Spawn());
            teamManager.getTeamSpec().setSpawn(this.arena.getSpectatorSpawn());
        } catch (Exception exception) {
            Logger.ERROR.log("Could not inject world object at arena " + this.arena.getName());
            exception.printStackTrace();
            return;
        }

        createInnerGameArea();
    }

    private void createInnerGameArea() {

        // Depending on the rotation of the (major) Game-Area, the spawn points 
        // of both teams are primarily on the X or Z axis opposite each other.
        // The Inner Game-Area is a copy of the (major) Game-Area, with the X or Z 
        // axis going only to spawn. The X or Z distance is thus reduced.
        // So this algorithm allows the spawn points to face each other even if 
        // they are offset.

        int x1, x2, z1, z2;
        Location position1, position2;

        if (gameArea.getDirection() == GameArea.Direction.NORTH_SOUTH) {

            x1 = gameArea.getMinX();
            x2 = gameArea.getMaxX();

            z1 = teamManager.getTeam1().getSpawn().getBlockZ();
            z2 = teamManager.getTeam2().getSpawn().getBlockZ();

        } else {

            z1 = gameArea.getMinZ();
            z2 = gameArea.getMaxZ();

            x1 = teamManager.getTeam1().getSpawn().getBlockX();
            x2 = teamManager.getTeam2().getSpawn().getBlockX();

        }

        position1 = new Location(gameArea.getWorld(), x1, gameArea.getMinY(), z1);
        position2 = new Location(gameArea.getWorld(), x2, gameArea.getMaxY(), z2);

        innerGameArea = new GameArea(position1, position2);
    }

    public void applyForAllPlayers(Consumer<Player> consumer) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isIn(player.getLocation())) continue;
            consumer.accept(player);
        }
    }

    /**
     * This method manages the message output of the game result.
     * Each player who is currently in the arena world gets a
     * customized message.
     */
    public void sendGameResult() {

        for (Player player : gameWorld.getWorld().getPlayers()) {
            MWPlayer mwPlayer = getPlayer(player);
            Team team = mwPlayer.getTeam();
            
            if (team.getTeamType() == TeamType.PLAYER) {
                team.sendMoney(mwPlayer);
                team.sendGameResultTitle(mwPlayer);
                team.sendGameResultSound(mwPlayer);
            } else {
                sendNeutralGameResultTitle(player);
            }
            
        }
    }

    /**
     * This method sends the players the title / subtitle of the
     * game result there are not in a team (= spectator).
     */
    public void sendNeutralGameResultTitle(Player player) {
        String title;
        String subTitle;

        if (teamManager.getTeam1().getGameResult() == GameResult.WIN) {
            title = Messages.getMessage(false, Messages.MessageEnum.GAME_RESULT_TITLE_WON)
                    .replace("%team%", teamManager.getTeam1().getName());
            subTitle = Messages.getMessage(false, Messages.MessageEnum.GAME_RESULT_SUBTITLE_WON);

        } else if (teamManager.getTeam2().getGameResult() == GameResult.WIN) {
            title = Messages.getMessage(false, Messages.MessageEnum.GAME_RESULT_TITLE_WON)
                    .replace("%team%", teamManager.getTeam2().getName());
            subTitle = Messages.getMessage(false, Messages.MessageEnum.GAME_RESULT_SUBTITLE_WON);

        } else {
            title = Messages.getMessage(false, Messages.MessageEnum.GAME_RESULT_TITLE_DRAW);
            subTitle = Messages.getMessage(false, Messages.MessageEnum.GAME_RESULT_SUBTITLE_DRAW);

        }

        player.sendTitle(title, subTitle);
    }

    /**
     * This method updates the MissileWars signs and the scoreboard.
     */
    public void updateGameInfo() {
        MissileWars.getInstance().getSignRepository().getSigns(this).forEach(MWSign::update);
        scoreboardManager.resetScoreboard();
        if (state == GameState.LOBBY) players.forEach((uuid, mwPlayer) -> mwPlayer.getGameJoinMenu().getMenu());
        
        Logger.DEBUG.log("Updated signs, scoreboard and menus.");
    }
    
    /**
     * This method checks whether there are too few players in 
     * the lobby based of the configuration. (Spectators are not 
     * counted here!)
     * 
     * @return (boolean) 'true' if to few players are in the lobby 
     */
    public boolean areToFewPlayers() {
        int minSize = lobby.getMinPlayers();
        int currentSize = teamManager.getTeam1().getMembers().size() + teamManager.getTeam2().getMembers().size();
        return currentSize < minSize;
    }

    /**
     * This method checks whether there are too many players in 
     * the lobby based of the configuration. (Spectators are not 
     * counted here!)
     * 
     * @return (boolean) 'true' if to many players are in the lobby 
     */
    public boolean areTooManyPlayers() {
        int maxSize = lobby.getMaxPlayers();
        
        if (maxSize == -1) return false;
        
        return getPlayerAmount() > maxSize;
    }
    
    /**
     * This method checks whether there are too many spectators in 
     * the lobby based of the configuration.
     * 
     * @return (boolean) 'true' if to many spectators are in the lobby 
     */
    public boolean areTooManySpectators() {
        int maxSize = lobby.getMaxSpectators();
        
        if (maxSize == -1) return false;
        
        int currentSize = teamManager.getTeamSpec().getMembers().size();
        return currentSize > maxSize;
    }
    
    public int getGameDuration() {
        int time = 0;
        if (arena == null) return time;
        
        if (state == GameState.LOBBY) {
            // Show the planned duration of the next game:
            time = arena.getGameDuration();
        } else if (state == GameState.INGAME) {
            // Show the remaining duration of the running game:
            time = getTaskManager().getTimer().getSeconds() / 60;
        } else if (state == GameState.END) {
            // Show the remaining duration of the last game:
            time = getRemainingGameDuration() / 60;
        }
        
        return time;
    }
    
    public int getTotalGameUserAmount() {
        return players.size();
    }
    
    public int getPlayerAmount() {
        if ((teamManager.getTeam1() == null) || (teamManager.getTeam2() == null)) return 0;
        return teamManager.getTeam1().getMembers().size() + teamManager.getTeam2().getMembers().size();
    }
    
    public List<String> getPlayerList() {
        List<String> playerList = new ArrayList<>();
        
        players.values().forEach(mwPlayer -> playerList.add(mwPlayer.getPlayer().getName()));
        
        return playerList;
    }

    public static void knockbackEffect(Player player, Location from, Location to) {
        Vector addTo = from.toVector().subtract(to.toVector()).multiply(3);
        addTo.setY(0);
        player.teleport(from.add(addTo));
    }

    public void teleportToFallbackSpawn(Player player) {
        teleportSafely(player, Config.getFallbackSpawn());
    }

    public void teleportToLobbySpawn(Player player) {
        teleportSafely(player, lobby.getSpawnPoint());
    }

    public void teleportToArenaSpectatorSpawn(Player player) {
        teleportSafely(player, arena.getSpectatorSpawn());
    }

    public void teleportToAfterGameSpawn(Player player) {
        teleportSafely(player, lobby.getAfterGameSpawn());
    }
    
    public static void teleportSafely(Player player, Location targetLocation) {
        player.setVelocity(new Vector(0, 0, 0));
        player.teleport(targetLocation);
    }
}
