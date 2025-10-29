package com.mboyd.racechrono;

import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.*;

import java.util.*;
import java.util.stream.Collectors;

public class RaceChrono extends JavaPlugin implements Listener {

    enum Mode { P2P, CIRCUIT }
    enum SignMode { BEST, TOP, LATEST }

    static class Track {
        String name;
        java.util.UUID owner;
        String description;
        Mode mode = Mode.P2P;
        LineGate startLine;
        LineGate finishLine;
        Region gate;
        int laps = 3;
        java.util.List<Region> splits = new java.util.ArrayList<>();
        Long bestMs; String bestHolder;
        Long latestMs; String latestHolder;
        java.util.List<ScoreRow> top = new java.util.ArrayList<>();
        Track(String name){ this.name=name; }
    }
    static class WorldData { java.util.Map<String, Track> tracks = new java.util.LinkedHashMap<>(); }
    static class ScoreRow { String player; long ms; ScoreRow(String p,long ms){this.player=p;this.ms=ms;} }
    static class BlockVec { String world; int x,y,z; BlockVec(String w,int x,int y,int z){this.world=w;this.x=x;this.y=y;this.z=z;} }
    static class SignBinding { SignMode mode; String track; BlockVec pos; SignBinding(SignMode m,String t,BlockVec p){mode=m;track=t;pos=p;} }

    private long maxRaceMs = 240_000L;
    private int displayPrecision = 2;

    private final java.util.Map<String, WorldData> worlds = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Long> activeStartsMs = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Long> activeStartsNano = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, String> activeTrack = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Integer> lapCount = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Integer> splitIndex = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, BukkitTask> actionBarTasks = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Scoreboard> boards = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, Objective> objectives = new java.util.HashMap<>();

    private final ParticleRenderer particleRenderer = ParticleRenderer.create();

    private final java.util.Map<java.util.UUID, org.bukkit.Location> pendingStart1 = new java.util.HashMap<>();
    private final java.util.Map<java.util.UUID, org.bukkit.Location> pendingFinish1 = new java.util.HashMap<>();
    private final java.util.Map<String, java.util.List<SignBinding>> signBindings = new java.util.HashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        loadFromConfig();
        getServer().getPluginManager().registerEvents(this, this);
        // Particle render
        Bukkit.getScheduler().runTaskTimer(this, this::renderAllLineParticles, 20L, 20L);

        Objects.requireNonNull(getCommand("racechrono")).setExecutor((sender, cmd, label, args) -> {
            if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
                sender.sendMessage(ChatColor.YELLOW + "RaceChrono v1.5 — /rc help");
                sender.sendMessage(cmd("/rc tracks list","list tracks"));
                sender.sendMessage(cmd("/rc tracks create <name...>","create track (you own it)"));
                sender.sendMessage(cmd("/rc tracks delete <name...>","delete track (owner/admin)"));
                sender.sendMessage(cmd("/rc tracks info <name...>","track details"));
                sender.sendMessage(cmd("/rc mode <P2P|CIRCUIT> [name...]","set mode"));
                sender.sendMessage(cmd("/rc setlaps <n> [name...]","set laps (circuit)"));
                sender.sendMessage(cmd("/rc setstartline [name...]","set start (two-step)"));
                sender.sendMessage(cmd("/rc setfinishline [name...]","set finish (two-step)"));
                sender.sendMessage(cmd("/rc setgate [radius] [name...]","set circuit gate (cube)"));
                sender.sendMessage(cmd("/rc addsplit [radius] [name...]","add split (cube)"));
                sender.sendMessage(cmd("/rc clearsplits [name...]","clear splits"));
                sender.sendMessage(cmd("/rc setdesc <track...> | <description>","set track description"));
                sender.sendMessage(cmd("/rc cleardesc [name...]","clear track description"));
                sender.sendMessage(cmd("/rc settimeout <sec>","timeout (global)"));
                sender.sendMessage(cmd("/rc setprecision <1|2|3>","display decimals"));
                sender.sendMessage(cmd("/rc best <name...>","show track best"));
                sender.sendMessage(cmd("/rc top <name...>","show track leaderboard"));
                sender.sendMessage(cmd("/rc sign bindbest <name...>","bind best sign"));
                sender.sendMessage(cmd("/rc sign bindtop <name...>","bind top-3 sign"));
                sender.sendMessage(cmd("/rc sign bindlatest <name...>","bind latest sign"));
                sender.sendMessage(cmd("/rc sign unbind","unbind sign"));
                sender.sendMessage(cmd("/rc cancel","cancel current run"));
                return true;
            }
            String sub = args[0].toLowerCase(java.util.Locale.ROOT);
            switch (sub) {
                case "tracks" -> {
                    if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /rc tracks <list|create|delete|info> [name...]"); return true; }
                    String op = args[1].toLowerCase(java.util.Locale.ROOT);
                    String w = (sender instanceof Player p) ? p.getWorld().getName() : Bukkit.getWorlds().get(0).getName();
                    WorldData wd = getWD(w);
                    if (op.equals("list")) {
                        if (wd.tracks.isEmpty()) { sender.sendMessage(ChatColor.AQUA + "Tracks in " + w + ": " + ChatColor.GRAY + "(none)"); return true; }
                        sender.sendMessage(ChatColor.AQUA + "Tracks in " + w + ":");
                        for (Track t : wd.tracks.values()) {
                            sender.sendMessage(summaryLine(t));
                            if (t.description != null && !t.description.isBlank()) sender.sendMessage(ChatColor.DARK_GRAY + "   " + t.description);
                        }
                        return true;
                    }
                    if (op.equals("create")) {
                        if (!sender.hasPermission("racechrono.admin") && !sender.hasPermission("racechrono.editor")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                        String name = joinArgs(args, 2); if (name.isBlank()) { sender.sendMessage(ChatColor.RED + "Track name required."); return true; }
                        if (wd.tracks.containsKey(name)) { sender.sendMessage(ChatColor.RED + "Track exists."); return true; }
                        Track t = new Track(name); if (sender instanceof Player p) t.owner = p.getUniqueId();
                        wd.tracks.put(name, t); saveToConfig(); sender.sendMessage(ChatColor.GREEN + "Created: " + name); return true;
                    }
                    if (op.equals("delete")) {
                        String name = joinArgs(args, 2); if (name.isBlank()) { sender.sendMessage(ChatColor.RED + "Track name required."); return true; }
                        Track t = wd.tracks.get(name); if (t == null) { sender.sendMessage(ChatColor.RED + "No such track."); return true; }
                        if (!sender.hasPermission("racechrono.admin")) {
                            if (!(sender instanceof Player p) || t.owner == null || !t.owner.equals(p.getUniqueId()) || !sender.hasPermission("racechrono.editor")) {
                                sender.sendMessage(ChatColor.RED + "You must own this track or be admin."); return true;
                            }
                        }
                        wd.tracks.remove(name); saveToConfig(); sender.sendMessage(ChatColor.YELLOW + "Deleted: " + name); removeSignsFor(w, name); return true;
                    }
                    if (op.equals("info")) {
                        String name = joinArgs(args, 2); if (name.isBlank()) { sender.sendMessage(ChatColor.RED + "Track name required."); return true; }
                        Track t = wd.tracks.get(name); if (t == null) { sender.sendMessage(ChatColor.RED + "No such track."); return true; }
                        sendTrackInfo(sender, w, t);
                        return true;
                    }
                    sender.sendMessage(ChatColor.RED + "Unknown tracks subcommand."); return true;
                }
                case "mode" -> {
                    if (!(sender instanceof Player p)) { sender.sendMessage("Players only"); return true; }
                    if (!canEdit(p)) { p.sendMessage(ChatColor.RED + "No permission."); return true; }
                    if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /rc mode <P2P|CIRCUIT> [name...]"); return true; }
                    Mode m; try { m = Mode.valueOf(args[1].toUpperCase(java.util.Locale.ROOT)); } catch (Exception ex) { sender.sendMessage(ChatColor.RED + "Mode must be P2P or CIRCUIT"); return true; }
                    String name = (args.length >= 3) ? joinArgs(args, 2) : null; if (name == null) { sender.sendMessage(ChatColor.RED + "Track name required."); return true; }
                    Track t = getWD(p.getWorld().getName()).tracks.computeIfAbsent(name, Track::new);
                    if (!isOwnerOrAdmin(p, t)) { p.sendMessage(ChatColor.RED + "You must own this track or be admin."); return true; }
                    t.mode = m; saveToConfig(); sender.sendMessage(ChatColor.GREEN + "Mode for " + name + " set to " + m); return true;
                }
                case "setlaps" -> {
                    if (!(sender instanceof Player p)) { sender.sendMessage("Players only"); return true; }
                    if (!canEdit(p)) { p.sendMessage(ChatColor.RED + "No permission."); return true; }
                    if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /rc setlaps <n> [name...]"); return true; }
                    int n; try { n = Integer.parseInt(args[1]); } catch (Exception ex) { sender.sendMessage(ChatColor.RED + "n must be a number"); return true; }
                    if (n < 1) n = 1;
                    String name = (args.length >= 3) ? joinArgs(args, 2) : null; if (name == null) { sender.sendMessage(ChatColor.RED + "Track name required."); return true; }
                    Track t = getWD(p.getWorld().getName()).tracks.computeIfAbsent(name, Track::new);
                    if (!isOwnerOrAdmin(p, t)) { p.sendMessage(ChatColor.RED + "You must own this track or be admin."); return true; }
                    t.laps = n; saveToConfig(); sender.sendMessage(ChatColor.GREEN + "Laps for " + name + " set to " + n); return true;
                }
                case "setgate" -> {
                    if (!(sender instanceof Player p)) { sender.sendMessage("Players only"); return true; }
                    if (!canEdit(p)) { p.sendMessage(ChatColor.RED + "No permission."); return true; }
                    int r = 2; String name = null;
                    if (args.length >= 2) {
                        Integer maybe = tryParseInt(args[1]); if (maybe != null) { r = maybe; if (args.length >= 3) name = joinArgs(args, 2); }
                        else name = joinArgs(args, 1);
                    }
                    if (name == null) { sender.sendMessage(ChatColor.RED + "Track name required."); return true; }
                    Track t = getWD(p.getWorld().getName()).tracks.computeIfAbsent(name, Track::new);
                    if (!isOwnerOrAdmin(p, t)) { p.sendMessage(ChatColor.RED + "You must own this track or be admin."); return true; }
                    t.gate = Region.fromCenterCube(p.getLocation(), r); saveToConfig(); sender.sendMessage(ChatColor.GREEN + "Gate set for " + name); return true;
                }
                case "addsplit" -> {
                    if (!(sender instanceof Player p)) { sender.sendMessage("Players only"); return true; }
                    if (!canEdit(p)) { p.sendMessage(ChatColor.RED + "No permission."); return true; }
                    int r = 2; String name = null;
                    if (args.length >= 2) {
                        Integer maybe = tryParseInt(args[1]); if (maybe != null) { r = maybe; if (args.length >= 3) name = joinArgs(args, 2); }
                        else name = joinArgs(args, 1);
                    }
                    if (name == null) { sender.sendMessage(ChatColor.RED + "Track name required."); return true; }
                    Track t = getWD(p.getWorld().getName()).tracks.computeIfAbsent(name, Track::new);
                    if (!isOwnerOrAdmin(p, t)) { p.sendMessage(ChatColor.RED + "You must own this track or be admin."); return true; }
                    t.splits.add(Region.fromCenterCube(p.getLocation(), r)); saveToConfig();
                    sender.sendMessage(ChatColor.GREEN + "Added split for " + name + " (total " + t.splits.size() + ")"); return true;
                }
                case "clearsplits" -> {
                    if (!(sender instanceof Player p)) { sender.sendMessage("Players only"); return true; }
                    if (!canEdit(p)) { p.sendMessage(ChatColor.RED + "No permission."); return true; }
                    String name = (args.length >= 2) ? joinArgs(args, 1) : null; if (name == null) { sender.sendMessage(ChatColor.RED + "Track name required."); return true; }
                    Track t = getWD(p.getWorld().getName()).tracks.get(name); if (t == null) { sender.sendMessage(ChatColor.RED + "No such track."); return true; }
                    if (!isOwnerOrAdmin(p, t)) { p.sendMessage(ChatColor.RED + "You must own this track or be admin."); return true; }
                    t.splits.clear(); saveToConfig(); sender.sendMessage(ChatColor.YELLOW + "Cleared splits for " + name); return true;
                }
                case "setdesc" -> {
                    if (!(sender instanceof Player p)) { sender.sendMessage("Players only"); return true; }
                    if (!canEdit(p)) { p.sendMessage(ChatColor.RED + "No permission."); return true; }
                    if (args.length < 2) { p.sendMessage(ChatColor.RED + "Usage: /rc setdesc <track...> | <description>"); return true; }
                    String raw = joinArgs(args, 1);
                    int pipe = raw.indexOf('|');
                    if (pipe < 0) { p.sendMessage(ChatColor.RED + "Separate track and description with |"); return true; }
                    String name = raw.substring(0, pipe).trim();
                    String desc = raw.substring(pipe + 1).trim();
                    if (name.isEmpty()) { p.sendMessage(ChatColor.RED + "Track name required."); return true; }
                    if (desc.isEmpty()) { p.sendMessage(ChatColor.RED + "Description cannot be empty."); return true; }
                    if (desc.length() > 120) { p.sendMessage(ChatColor.RED + "Description too long (max 120 chars)."); return true; }
                    Track t = getWD(p.getWorld().getName()).tracks.get(name); if (t == null) { p.sendMessage(ChatColor.RED + "No such track."); return true; }
                    if (!isOwnerOrAdmin(p, t)) { p.sendMessage(ChatColor.RED + "You must own this track or be admin."); return true; }
                    t.description = desc;
                    saveToConfig();
                    p.sendMessage(ChatColor.GREEN + "Description updated for " + name);
                    return true;
                }
                case "cleardesc" -> {
                    if (!(sender instanceof Player p)) { sender.sendMessage("Players only"); return true; }
                    if (!canEdit(p)) { p.sendMessage(ChatColor.RED + "No permission."); return true; }
                    String name = (args.length >= 2) ? joinArgs(args, 1) : null; if (name == null) { sender.sendMessage(ChatColor.RED + "Track name required."); return true; }
                    Track t = getWD(p.getWorld().getName()).tracks.get(name); if (t == null) { sender.sendMessage(ChatColor.RED + "No such track."); return true; }
                    if (!isOwnerOrAdmin(p, t)) { p.sendMessage(ChatColor.RED + "You must own this track or be admin."); return true; }
                    t.description = null;
                    saveToConfig();
                    sender.sendMessage(ChatColor.YELLOW + "Cleared description for " + name);
                    return true;
                }
                case "setstartline" -> {
                    if (!(sender instanceof Player p)) { sender.sendMessage("Players only"); return true; }
                    if (!canEdit(p)) { p.sendMessage(ChatColor.RED + "No permission."); return true; }
                    String name = (args.length >= 2) ? joinArgs(args, 1) : null; if (name == null) { sender.sendMessage(ChatColor.RED + "Track name required."); return true; }
                    Track t = getWD(p.getWorld().getName()).tracks.computeIfAbsent(name, Track::new);
                    if (!isOwnerOrAdmin(p, t)) { p.sendMessage(ChatColor.RED + "You must own this track or be admin."); return true; }
                    java.util.UUID id = p.getUniqueId();
                    if (!pendingStart1.containsKey(id)) { pendingStart1.put(id, p.getLocation()); p.sendMessage(ChatColor.GOLD + "Start line A set. Run again to set B."); }
                    else {
                        org.bukkit.Location a = pendingStart1.remove(id);
                        try { t.startLine = LineGate.fromTwoPoints(a, p.getLocation()); saveToConfig(); p.sendMessage(ChatColor.GREEN + "Start line set for " + name); }
                        catch (IllegalArgumentException ex) { p.sendMessage(ChatColor.RED + "Points must align on X or Z and be 1 block wide."); }
                    } return true;
                }
                case "setfinishline" -> {
                    if (!(sender instanceof Player p)) { sender.sendMessage("Players only"); return true; }
                    if (!canEdit(p)) { p.sendMessage(ChatColor.RED + "No permission."); return true; }
                    String name = (args.length >= 2) ? joinArgs(args, 1) : null; if (name == null) { sender.sendMessage(ChatColor.RED + "Track name required."); return true; }
                    Track t = getWD(p.getWorld().getName()).tracks.computeIfAbsent(name, Track::new);
                    if (!isOwnerOrAdmin(p, t)) { p.sendMessage(ChatColor.RED + "You must own this track or be admin."); return true; }
                    java.util.UUID id = p.getUniqueId();
                    if (!pendingFinish1.containsKey(id)) { pendingFinish1.put(id, p.getLocation()); p.sendMessage(ChatColor.GOLD + "Finish line A set. Run again to set B."); }
                    else {
                        org.bukkit.Location a = pendingFinish1.remove(id);
                        try { t.finishLine = LineGate.fromTwoPoints(a, p.getLocation()); saveToConfig(); p.sendMessage(ChatColor.GREEN + "Finish line set for " + name); }
                        catch (IllegalArgumentException ex) { p.sendMessage(ChatColor.RED + "Points must align on X or Z and be 1 block wide."); }
                    } return true;
                }
                case "settimeout" -> {
                    if (!sender.hasPermission("racechrono.admin")) { sender.sendMessage(ChatColor.RED + "No permission"); return true; }
                    if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /rc settimeout <seconds>"); return true; }
                    int s; try { s = Integer.parseInt(args[1]); } catch (Exception ex) { sender.sendMessage(ChatColor.RED + "seconds must be a number"); return true; }
                    if (s < 5) s = 5; maxRaceMs = s * 1000L; getConfig().set("maxRaceTimeSeconds", s); saveConfig(); sender.sendMessage(ChatColor.GREEN + "Timeout set to " + s + "s"); return true;
                }
                case "setprecision" -> {
                    if (!sender.hasPermission("racechrono.admin")) { sender.sendMessage(ChatColor.RED + "No permission"); return true; }
                    if (args.length < 2) { sender.sendMessage(ChatColor.RED + "Usage: /rc setprecision <1|2|3>"); return true; }
                    int pval; try { pval = Integer.parseInt(args[1]); } catch (Exception ex) { sender.sendMessage(ChatColor.RED + "precision must be 1..3"); return true; }
                    if (pval < 1) pval = 1; if (pval > 3) pval = 3; displayPrecision = pval; getConfig().set("displayPrecision", pval); saveConfig(); sender.sendMessage(ChatColor.GREEN + "Display precision set to " + pval); return true;
                }
                case "best" -> { return showBest(sender, args); }
                case "top" -> { return showTop(sender, args); }
                case "resetbest" -> {
                    if (!sender.hasPermission("racechrono.admin")) { sender.sendMessage(ChatColor.RED + "No permission"); return true; }
                    String w = (sender instanceof Player p) ? p.getWorld().getName() : Bukkit.getWorlds().get(0).getName();
                    WorldData wd = getWD(w);
                    String name = (args.length >= 2) ? joinArgs(args, 1) : null;
                    if (name == null || !wd.tracks.containsKey(name)) { sender.sendMessage(ChatColor.RED + "No such track."); return true; }
                    Track t = wd.tracks.get(name);
                    t.bestMs = null; t.bestHolder = null; t.latestMs = null; t.latestHolder = null; t.top.clear(); saveToConfig(); refreshSignsFor(w, name); sender.sendMessage(ChatColor.YELLOW + "Cleared " + name); return true;
                }
                case "sign" -> {
                    if (!(sender instanceof Player p)) { sender.sendMessage("Players only"); return true; }
                    if (!p.hasPermission("racechrono.sign")) { p.sendMessage(ChatColor.RED + "No permission."); return true; }
                    if (args.length < 2) { p.sendMessage(ChatColor.RED + "Usage: /rc sign <bindbest|bindtop|bindlatest|unbind> [track...]"); return true; }
                    String op = args[1].toLowerCase(java.util.Locale.ROOT);
                    Block target = p.getTargetBlockExact(6);
                    if (target == null || !(target.getState() instanceof Sign)) { p.sendMessage(ChatColor.RED + "Look at a sign within 6 blocks."); return true; }
                    String world = p.getWorld().getName();
                    if (op.equals("unbind")) { unbindSign(world, target); p.sendMessage(ChatColor.YELLOW + "Sign unbound."); return true; }
                    String name = (args.length >= 3) ? joinArgs(args, 2) : null; if (name == null) { p.sendMessage(ChatColor.RED + "Specify track name."); return true; }
                    WorldData wd = getWD(world); if (!wd.tracks.containsKey(name)) { p.sendMessage(ChatColor.RED + "No such track."); return true; }
                    SignMode mode;
                    if (op.equals("bindbest")) mode = SignMode.BEST;
                    else if (op.equals("bindtop")) mode = SignMode.TOP;
                    else if (op.equals("bindlatest")) mode = SignMode.LATEST;
                    else { p.sendMessage(ChatColor.RED + "Unknown sign mode."); return true; }
                    bindSign(world, name, mode, target); p.sendMessage(ChatColor.GREEN + "Sign bound (" + mode + ") to " + name); refreshSignsFor(world, name); return true;
                }
                case "cancel" -> { if (!(sender instanceof Player p)) { sender.sendMessage("Players only"); return true; } cancelRun(p); return true; }
                default -> { sender.sendMessage(ChatColor.RED + "Unknown subcommand."); return true; }
            }
        });
    }

    private boolean canEdit(Player p){ return p.hasPermission("racechrono.admin") || p.hasPermission("racechrono.editor"); }
    private boolean isOwnerOrAdmin(Player p, Track t){ return p.hasPermission("racechrono.admin") || (p.hasPermission("racechrono.editor") && t.owner != null && t.owner.equals(p.getUniqueId())); }
    private String cmd(String c, String d){ return ChatColor.GOLD + c + ChatColor.GRAY + " — " + d; }

    @Override public void onDisable(){ saveToConfig(); }
    @EventHandler public void onQuit(PlayerQuitEvent e){ cancelRun(e.getPlayer()); }

    @EventHandler public void onVehicleMove(VehicleMoveEvent e) {
        if (!(e.getVehicle() instanceof Boat boat)) return;
        if (boat.getPassengers().isEmpty() || !(boat.getPassengers().get(0) instanceof Player p)) return;
        String world = p.getWorld().getName();
        WorldData wd = worlds.get(world); if (wd == null) return;
        org.bukkit.Location to = e.getTo(); java.util.UUID id = p.getUniqueId();

        if (!activeStartsMs.containsKey(id)) {
            for (Track t : wd.tracks.values()) {
                if (t.mode == Mode.P2P) {
                    if (t.startLine != null && t.startLine.contains(to)) { startRun(p, t.name); return; }
                } else {
                    if (t.gate != null && t.gate.contains(to)) { startRun(p, t.name); lapCount.put(id, 0); return; }
                }
            } return;
        }

        String tn = activeTrack.get(id); if (tn == null) return;
        Track t = wd.tracks.get(tn); if (t == null) return;

        if (!t.splits.isEmpty()) {
            int idx = splitIndex.getOrDefault(id, 0);
            if (idx < t.splits.size() && t.splits.get(idx).contains(to)) { splitIndex.put(id, idx+1); showSplit(p, idx+1, t.splits.size()); }
        }

        if (t.mode == Mode.P2P) {
            if (t.finishLine != null && t.finishLine.contains(to)) { finishRun(p, t, tn); }
        } else {
            if (t.gate != null && t.gate.contains(to)) {
                int laps = lapCount.getOrDefault(id, 0) + 1; lapCount.put(id, laps);
                if (laps >= t.laps) finishRun(p, t, tn);
                else { p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.YELLOW + "Lap " + laps + "/" + t.laps)); updateSidebar(p, t); }
            }
        }
    }

    private void showSplit(Player p, int n, int total){ p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.YELLOW + "Split " + n + "/" + total)); updateSidebar(p, getCurrentTrack(p)); }
    private Track getCurrentTrack(Player p){ String tn = activeTrack.get(p.getUniqueId()); if (tn == null) return null; WorldData wd = worlds.get(p.getWorld().getName()); if (wd == null) return null; return wd.tracks.get(tn); }

    private void startRun(Player p, String trackName) {
        if (!p.hasPermission("racechrono.use")) { p.sendMessage(ChatColor.RED + "You do not have permission to participate in races."); return; }
        java.util.UUID id = p.getUniqueId();
        activeStartsMs.put(id, System.currentTimeMillis());
        activeStartsNano.put(id, System.nanoTime());
        activeTrack.put(id, trackName);
        splitIndex.remove(id);
        startActionBar(id, p);
        createSidebar(p, trackName);
        p.sendMessage(ChatColor.GREEN + "Go! Track: " + trackName);
    }
    private void finishRun(Player p, Track t, String trackName) {
        java.util.UUID id = p.getUniqueId();
        Long start = activeStartsMs.remove(id);
        activeStartsNano.remove(id); activeTrack.remove(id); lapCount.remove(id); splitIndex.remove(id);
        stopActionBar(id); removeSidebar(p);
        if (start == null) return;
        long elapsed = System.currentTimeMillis() - start;
        Long previousBest = t.bestMs;
        t.latestMs = elapsed; t.latestHolder = p.getName();
        String deltaText = (previousBest == null) ? "" : ChatColor.GRAY + " (" + formatDelta(elapsed - previousBest) + ChatColor.GRAY + " vs best)";
        p.sendMessage(ChatColor.AQUA + "Finish (" + trackName + ")! Time: " + ChatColor.GOLD + formatTime(elapsed) + deltaText);
        boolean newRecord = previousBest == null || elapsed < previousBest;
        for (Player pl : Bukkit.getOnlinePlayers()) {
            StringBuilder msg = new StringBuilder();
            msg.append(ChatColor.AQUA).append(p.getName()).append(" — ").append(ChatColor.GOLD).append(formatTime(elapsed)).append(ChatColor.GRAY).append(" (").append(trackName).append(")");
            if (newRecord) {
                msg.append(ChatColor.GOLD).append(" [New PB");
                if (previousBest != null) msg.append(" ").append(formatDelta(elapsed - previousBest));
                msg.append(ChatColor.GOLD).append("]");
            } else if (previousBest != null) {
                msg.append(ChatColor.GRAY).append(" [").append(formatDelta(elapsed - previousBest)).append(ChatColor.GRAY).append("]");
            }
            pl.sendMessage(msg.toString());
        }
        if (newRecord) { t.bestMs = elapsed; t.bestHolder = p.getName(); p.sendTitle(ChatColor.GOLD + "New Best (" + trackName + ")!", ChatColor.WHITE + formatTime(elapsed), 10, 40, 10); }
        t.top.add(new ScoreRow(p.getName(), elapsed)); t.top.sort(java.util.Comparator.comparingLong(s -> s.ms)); if (t.top.size() > 10) t.top = new java.util.ArrayList<>(t.top.subList(0, 10));
        saveToConfig(); refreshSignsFor(p.getWorld().getName(), trackName);
    }
    private void cancelRun(Player p){ java.util.UUID id=p.getUniqueId(); if (activeStartsMs.remove(id)!=null){ activeTrack.remove(id); lapCount.remove(id); splitIndex.remove(id); activeStartsNano.remove(id); stopActionBar(id); removeSidebar(p); p.sendMessage(ChatColor.RED + "Run cancelled."); } else p.sendMessage(ChatColor.GRAY + "No active run."); }

    private void startActionBar(java.util.UUID id, Player p) {
        stopActionBar(id);
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            Long nano = activeStartsNano.get(id); if (nano == null) return;
            double seconds = (System.nanoTime() - nano) / 1_000_000_000.0;
            p.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(ChatColor.GREEN + formatTime(seconds)));
            updateSidebar(p, getCurrentTrack(p));
            Long startMs = activeStartsMs.get(id);
            if (startMs != null && System.currentTimeMillis() - startMs > maxRaceMs) { p.sendMessage(ChatColor.RED + "Run cancelled (timeout)."); cancelRun(p); }
        }, 0L, 2L);
        actionBarTasks.put(id, task);
    }
    private void stopActionBar(java.util.UUID id){ BukkitTask t = actionBarTasks.remove(id); if (t!=null) t.cancel(); }

    private String formatTime(double seconds){ int p = displayPrecision; if (p<=1) return String.format(java.util.Locale.US,"%.1fs",seconds); if (p==2) return String.format(java.util.Locale.US,"%.2fs",seconds); return String.format(java.util.Locale.US,"%.3fs",seconds); }
    private String formatTime(long ms){ return formatTime(ms/1000.0); }
    private String formatDeltaSeconds(double delta){ ChatColor color = delta < 0 ? ChatColor.GREEN : (delta > 0 ? ChatColor.RED : ChatColor.GRAY); String sign = delta == 0 ? "±" : (delta > 0 ? "+" : "-"); return color + sign + formatTime(Math.abs(delta)); }
    private String formatDelta(long deltaMs){ return formatDeltaSeconds(deltaMs/1000.0); }

    // Sidebar
    private void createSidebar(Player p, String track) {
        removeSidebar(p); ScoreboardManager mgr = Bukkit.getScoreboardManager(); if (mgr == null) return;
        Scoreboard board = mgr.getNewScoreboard(); Objective obj = board.registerNewObjective("racechrono","dummy", ChatColor.GOLD + "RaceChrono");
        obj.setDisplaySlot(DisplaySlot.SIDEBAR); boards.put(p.getUniqueId(), board); objectives.put(p.getUniqueId(), obj); p.setScoreboard(board); updateSidebar(p, getWD(p.getWorld().getName()).tracks.get(track));
    }
    private void updateSidebar(Player p, Track t) {
        Objective obj = objectives.get(p.getUniqueId()); Scoreboard board = boards.get(p.getUniqueId()); if (obj==null||board==null) return;
        for (String e : new java.util.ArrayList<>(board.getEntries())) board.resetScores(e);
        String trackName = (t==null) ? "(none)" : t.name;
        obj.getScore(ChatColor.YELLOW + trackName).setScore(8);
        Long nano = activeStartsNano.get(p.getUniqueId()); double secs = (nano==null)?0.0:(System.nanoTime()-nano)/1_000_000_000.0;
        obj.getScore(ChatColor.WHITE + "Time: " + ChatColor.AQUA + formatTime(secs)).setScore(7);
        if (t != null && !t.splits.isEmpty()) {
            int idx = splitIndex.getOrDefault(p.getUniqueId(), 0);
            obj.getScore(ChatColor.WHITE + "Split: " + ChatColor.AQUA + idx + "/" + t.splits.size()).setScore(6);
        }
        if (t != null && t.mode == Mode.CIRCUIT) {
            int lap = lapCount.getOrDefault(p.getUniqueId(), 0);
            obj.getScore(ChatColor.WHITE + "Lap: " + ChatColor.AQUA + lap + "/" + t.laps).setScore(5);
        }
        if (t != null && t.bestMs != null) {
            obj.getScore(ChatColor.WHITE + "PB: " + ChatColor.AQUA + formatTime(t.bestMs)).setScore(4);
            obj.getScore(ChatColor.WHITE + "Δ Best: " + formatDeltaSeconds(secs - (t.bestMs / 1000.0))).setScore(3);
        }
    }
    private void removeSidebar(Player p){ boards.remove(p.getUniqueId()); objectives.remove(p.getUniqueId()); ScoreboardManager m=Bukkit.getScoreboardManager(); if (m!=null) p.setScoreboard(m.getMainScoreboard()); }

    // Signs
    private void bindSign(String world, String track, SignMode mode, Block block){ SignBinding sb = new SignBinding(mode, track, new BlockVec(world, block.getX(), block.getY(), block.getZ())); signBindings.computeIfAbsent(world,k->new java.util.ArrayList<>()).add(sb); saveToConfig(); }
    private void unbindSign(String world, Block block){ java.util.List<SignBinding> list = signBindings.get(world); if (list==null) return; list.removeIf(s -> s.pos.x==block.getX() && s.pos.y==block.getY() && s.pos.z==block.getZ()); saveToConfig(); if (block.getState() instanceof Sign sign){ for(int i=0;i<4;i++) sign.setLine(i,""); sign.update(); } }
    private void refreshSignsFor(String world, String track){
        java.util.List<SignBinding> list = signBindings.get(world); if (list==null) return;
        World w = Bukkit.getWorld(world); if (w==null) return; Track t = getWD(world).tracks.get(track);
        for (SignBinding sb : new java.util.ArrayList<>(list)) {
            if (!sb.track.equals(track)) continue;
            Block b = w.getBlockAt(sb.pos.x, sb.pos.y, sb.pos.z); if (!(b.getState() instanceof Sign sign)) continue;
            if (sb.mode == SignMode.TOP) continue; // handled below for formatting
            sign.setLine(0, ChatColor.GOLD + "RaceChrono");
            sign.setLine(1, ChatColor.WHITE + track);
            if (sb.mode == SignMode.BEST) {
                String best = (t!=null && t.bestMs!=null) ? formatTime(t.bestMs) : "—";
                String holder = (t!=null && t.bestHolder!=null) ? t.bestHolder : "";
                sign.setLine(2, ChatColor.AQUA + "Best: " + best);
                sign.setLine(3, holder.isEmpty()? "": ChatColor.GRAY + holder);
            } else if (sb.mode == SignMode.LATEST) {
                String latest = (t!=null && t.latestMs!=null) ? formatTime(t.latestMs) : "—";
                String holder = (t!=null && t.latestHolder!=null) ? t.latestHolder : "";
                sign.setLine(2, ChatColor.AQUA + "Last: " + latest);
                sign.setLine(3, holder.isEmpty()? "": ChatColor.GRAY + holder);
            }
            sign.update();
        }
        for (SignBinding sb : new java.util.ArrayList<>(list)) {
            if (sb.mode != SignMode.TOP) continue; if (!sb.track.equals(track)) continue;
            World w2 = Bukkit.getWorld(world); if (w2==null) continue; Block b = w2.getBlockAt(sb.pos.x, sb.pos.y, sb.pos.z); if (!(b.getState() instanceof Sign sign)) continue;
            sign.setLine(0, ChatColor.GOLD + "Top 3");
            if (t==null || t.top.isEmpty()) { sign.setLine(1, ChatColor.GRAY + track); sign.setLine(2, ChatColor.DARK_GRAY + "(no runs)"); sign.setLine(3, ""); }
            else {
                String l1 = topEntry(1, t.top, 0);
                String l2 = topEntry(2, t.top, 1);
                String l3 = topEntry(3, t.top, 2);
                sign.setLine(1, l1); sign.setLine(2, l2); sign.setLine(3, l3);
            } sign.update();
        }
    }
    private String topEntry(int rank, java.util.List<ScoreRow> list, int idx){ if (idx>=list.size()) return ""; ScoreRow s=list.get(idx); String name=s.player; if (name.length()>12) name=name.substring(0,12); return ChatColor.GOLD + "" + rank + ") " + ChatColor.WHITE + name + ChatColor.GRAY + " " + formatTime(s.ms); }
    private void removeSignsFor(String world, String track){ java.util.List<SignBinding> list=signBindings.get(world); if (list==null) return; list.removeIf(sb -> sb.track.equals(track)); saveToConfig(); }

    private String summaryLine(Track t){
        StringBuilder sb = new StringBuilder();
        sb.append(ChatColor.GOLD).append("- ").append(t.name);
        java.util.List<String> bits = new java.util.ArrayList<>();
        bits.add(t.mode == Mode.CIRCUIT ? "Circuit" : "P2P");
        if (t.mode == Mode.CIRCUIT) bits.add(t.laps + " laps");
        if (!t.splits.isEmpty()) bits.add(t.splits.size() + " splits");
        if (t.bestMs != null) bits.add("PB " + formatTime(t.bestMs));
        if (!bits.isEmpty()) {
            sb.append(ChatColor.GRAY).append(" [");
            for (int i = 0; i < bits.size(); i++) {
                if (i > 0) sb.append(ChatColor.GRAY).append(", ");
                sb.append(ChatColor.WHITE).append(bits.get(i));
            }
            sb.append(ChatColor.GRAY).append("]");
        }
        return sb.toString();
    }

    private void sendTrackInfo(org.bukkit.command.CommandSender sender, String world, Track t){
        sender.sendMessage(ChatColor.AQUA + "Track info — " + ChatColor.GOLD + t.name + ChatColor.GRAY + " (" + world + ")");
        if (t.description != null && !t.description.isBlank()) sender.sendMessage(ChatColor.DARK_GRAY + t.description);
        sender.sendMessage(ChatColor.WHITE + "Mode: " + ChatColor.AQUA + (t.mode == Mode.CIRCUIT ? "Circuit" : "P2P"));
        if (t.mode == Mode.CIRCUIT) sender.sendMessage(ChatColor.WHITE + "Laps: " + ChatColor.AQUA + t.laps);
        sender.sendMessage(ChatColor.WHITE + "Splits: " + (t.splits.isEmpty() ? ChatColor.GRAY + "—" : ChatColor.AQUA + String.valueOf(t.splits.size())));
        sender.sendMessage(ChatColor.WHITE + "Start line: " + (t.startLine == null ? ChatColor.GRAY + "—" : ChatColor.AQUA + "set"));
        sender.sendMessage(ChatColor.WHITE + "Finish line: " + (t.finishLine == null ? ChatColor.GRAY + "—" : ChatColor.AQUA + "set"));
        if (t.mode == Mode.CIRCUIT) sender.sendMessage(ChatColor.WHITE + "Gate: " + (t.gate == null ? ChatColor.GRAY + "—" : ChatColor.AQUA + "set"));
        if (t.owner != null) {
            org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(t.owner);
            String ownerName = op.getName() != null ? op.getName() : t.owner.toString();
            sender.sendMessage(ChatColor.WHITE + "Owner: " + ChatColor.AQUA + ownerName);
        }
        String best = (t.bestMs == null) ? ChatColor.GRAY + "—" : ChatColor.AQUA + formatTime(t.bestMs) + (t.bestHolder == null ? "" : ChatColor.GRAY + " by " + t.bestHolder);
        String latest = (t.latestMs == null) ? ChatColor.GRAY + "—" : ChatColor.AQUA + formatTime(t.latestMs) + (t.latestHolder == null ? "" : ChatColor.GRAY + " by " + t.latestHolder);
        sender.sendMessage(ChatColor.WHITE + "Best: " + best);
        sender.sendMessage(ChatColor.WHITE + "Latest: " + latest);
        if (!t.top.isEmpty()) sender.sendMessage(ChatColor.WHITE + "Leaderboard entries: " + ChatColor.AQUA + t.top.size());
    }

    // Particles (chequered flags)
    private void renderAllLineParticles(){
        if (!particleRenderer.isAvailable()) return;
        for (java.util.Map.Entry<String, WorldData> we : worlds.entrySet()) {
            World w = Bukkit.getWorld(we.getKey()); if (w==null) continue;
            for (Track t : we.getValue().tracks.values()) {
                if (t.startLine != null) renderChequered(w, t.startLine);
                if (t.finishLine != null) renderChequered(w, t.finishLine);
            }
        }
    }
    private void renderChequered(World w, LineGate g){
        int x1=g.getX1(), y1=g.getY1(), z1=g.getZ1();
        int x2=g.getX2(), y2=g.getY2(), z2=g.getZ2();
        int minY=Math.min(y1,y2), maxY=Math.max(y1,y2);
        if (x1==x2){
            int x=x1; int minZ=Math.min(z1,z2), maxZ=Math.max(z1,z2);
            for (int z=minZ; z<=maxZ; z++) for (int y=minY; y<=maxY; y++) {
                particleRenderer.spawnChequer(w, x + 0.5, y + 0.5, z + 0.5, ((z + y) % 2 == 0));
            }
        } else {
            int z=z1; int minX=Math.min(x1,x2), maxX=Math.max(x1,x2);
            for (int x=minX; x<=maxX; x++) for (int y=minY; y<=maxY; y++) {
                particleRenderer.spawnChequer(w, x + 0.5, y + 0.5, z + 0.5, ((x + y) % 2 == 0));
            }
        }
    }

    private static final class ParticleRenderer {
        private static final double OFFSET_VARIANCE = 0.0D;

        private final java.lang.reflect.Method spawnParticle;
        private final java.lang.reflect.Constructor<?> dustOptionsConstructor;
        private final Object redstoneParticle;
        private final Object whiteColor;
        private final Object blackColor;

        private ParticleRenderer(java.lang.reflect.Method spawnParticle,
                                 java.lang.reflect.Constructor<?> dustOptionsConstructor,
                                 Object redstoneParticle,
                                 Object whiteColor,
                                 Object blackColor) {
            this.spawnParticle = spawnParticle;
            this.dustOptionsConstructor = dustOptionsConstructor;
            this.redstoneParticle = redstoneParticle;
            this.whiteColor = whiteColor;
            this.blackColor = blackColor;
        }

        static ParticleRenderer create() {
            try {
                Class<?> particleClass = Class.forName("org.bukkit.Particle");
                Class<?> dustClass = Class.forName("org.bukkit.Particle$DustOptions");
                Class<?> colorClass = Class.forName("org.bukkit.Color");
                java.lang.reflect.Method spawn = World.class.getMethod("spawnParticle",
                    particleClass, double.class, double.class, double.class, int.class,
                    double.class, double.class, double.class, double.class, Object.class, boolean.class);
                @SuppressWarnings("unchecked")
                Object redstone = Enum.valueOf((Class<Enum>) particleClass, "REDSTONE");
                java.lang.reflect.Constructor<?> ctor = dustClass.getConstructor(colorClass, float.class);
                java.lang.reflect.Method fromRGB = colorClass.getMethod("fromRGB", int.class, int.class, int.class);
                Object white = fromRGB.invoke(null, 255, 255, 255);
                Object black = fromRGB.invoke(null, 0, 0, 0);
                return new ParticleRenderer(spawn, ctor, redstone, white, black);
            } catch (Throwable ignored) {
                return new ParticleRenderer(null, null, null, null, null);
            }
        }

        boolean isAvailable() {
            return spawnParticle != null && dustOptionsConstructor != null && redstoneParticle != null && whiteColor != null && blackColor != null;
        }

        void spawnChequer(World world, double x, double y, double z, boolean white) {
            if (!isAvailable()) return;
            try {
                Object dust = dustOptionsConstructor.newInstance(white ? whiteColor : blackColor, Float.valueOf(1.2F));
                spawnParticle.invoke(world, redstoneParticle, x, y, z, 1, OFFSET_VARIANCE, OFFSET_VARIANCE, OFFSET_VARIANCE, 0.0D, dust, Boolean.TRUE);
            } catch (Throwable ignored) {
                // gracefully skip if particles are unavailable at runtime
            }
        }
    }

    private boolean showBest(org.bukkit.command.CommandSender sender, String[] args){
        String w = (sender instanceof Player p) ? p.getWorld().getName() : Bukkit.getWorlds().get(0).getName();
        WorldData wd = getWD(w);
        String name = (args.length >= 2) ? joinArgs(args, 1) : null;
        if (name == null || !wd.tracks.containsKey(name)) { sender.sendMessage(ChatColor.RED + "No such track."); return true; }
        Track t = wd.tracks.get(name);
        String best = (t.bestMs == null) ? "—" : formatTime(t.bestMs);
        String holder = (t.bestHolder == null) ? "" : (" by " + t.bestHolder);
        sender.sendMessage(ChatColor.AQUA + "Best (" + name + "): " + ChatColor.GOLD + best + ChatColor.GRAY + holder);
        return true;
    }
    private boolean showTop(org.bukkit.command.CommandSender sender, String[] args){
        String w = (sender instanceof Player p) ? p.getWorld().getName() : Bukkit.getWorlds().get(0).getName();
        WorldData wd = getWD(w);
        String name = (args.length >= 2) ? joinArgs(args, 1) : null;
        if (name == null || !wd.tracks.containsKey(name)) { sender.sendMessage(ChatColor.RED + "No such track."); return true; }
        Track t = wd.tracks.get(name);
        sender.sendMessage(ChatColor.YELLOW + "Top 10 — " + name);
        if (t.top.isEmpty()) { sender.sendMessage(ChatColor.GRAY + "(no entries yet)"); return true; }
        int rank=1; for (ScoreRow s : t.top){ sender.sendMessage(ChatColor.GOLD + "" + rank + ". " + ChatColor.WHITE + s.player + ChatColor.GRAY + " — " + ChatColor.AQUA + formatTime(s.ms)); if (++rank>10) break; }
        return true;
    }

    private WorldData getWD(String world){ return worlds.computeIfAbsent(world, w -> new WorldData()); }

    private void loadFromConfig(){
        FileConfiguration c = getConfig();
        int secs = c.getInt("maxRaceTimeSeconds", 240); if (secs<5) secs=5; maxRaceMs = secs*1000L;
        displayPrecision = Math.max(1, Math.min(3, c.getInt("displayPrecision", 2)));

        ConfigurationSection ws = c.getConfigurationSection("worlds");
        if (ws != null) {
            for (String wname : ws.getKeys(false)) {
                ConfigurationSection wsec = ws.getConfigurationSection(wname); if (wsec == null) continue;
                WorldData wd = new WorldData();
                ConfigurationSection ts = wsec.getConfigurationSection("tracks");
                if (ts != null) for (String tname : ts.getKeys(false)) {
                    ConfigurationSection tsec = ts.getConfigurationSection(tname);
                    Track t = new Track(tname);
                        if (tsec != null) {
                            String ownerStr = tsec.getString("owner", null); if (ownerStr != null) try { t.owner = java.util.UUID.fromString(ownerStr); } catch (Exception ignored){}
                            t.mode = Mode.valueOf(tsec.getString("mode", "P2P"));
                            t.description = tsec.getString("description", null);
                            if (t.description != null) { t.description = t.description.trim(); if (t.description.isBlank()) t.description = null; }
                            if (tsec.contains("startLine")) {
                                ConfigurationSection s = tsec.getConfigurationSection("startLine");
                                t.startLine = new LineGate(wname, s.getInt("x1"), s.getInt("y1"), s.getInt("z1"), s.getInt("x2"), s.getInt("y2"), s.getInt("z2"));
                            }
                        if (tsec.contains("finishLine")) {
                            ConfigurationSection s = tsec.getConfigurationSection("finishLine");
                            t.finishLine = new LineGate(wname, s.getInt("x1"), s.getInt("y1"), s.getInt("z1"), s.getInt("x2"), s.getInt("y2"), s.getInt("z2"));
                        }
                        if (tsec.contains("gate")) {
                            ConfigurationSection s = tsec.getConfigurationSection("gate");
                            t.gate = new Region(wname, new org.bukkit.util.BoundingBox(
                                s.getDouble("minX"), s.getDouble("minY"), s.getDouble("minZ"),
                                s.getDouble("maxX"), s.getDouble("maxY"), s.getDouble("maxZ")));
                        }
                        if (tsec.contains("splits")) {
                            for (String key : tsec.getConfigurationSection("splits").getKeys(false)) {
                                ConfigurationSection s = tsec.getConfigurationSection("splits." + key);
                                t.splits.add(new Region(wname, new org.bukkit.util.BoundingBox(
                                    s.getDouble("minX"), s.getDouble("minY"), s.getDouble("minZ"),
                                    s.getDouble("maxX"), s.getDouble("maxY"), s.getDouble("maxZ"))));
                            }
                        }
                        t.laps = tsec.getInt("laps", 3);
                        if (tsec.contains("best")) t.bestMs = tsec.getLong("best");
                        t.bestHolder = tsec.getString("bestHolder", null);
                        if (tsec.contains("latest")) t.latestMs = tsec.getLong("latest");
                        t.latestHolder = tsec.getString("latestHolder", null);
                        if (tsec.contains("leaderboard")) {
                            java.util.List<String> lines = tsec.getStringList("leaderboard");
                            for (String line : lines) { int idx = line.lastIndexOf(':'); if (idx>0) { String player=line.substring(0,idx); try { long ms=Long.parseLong(line.substring(idx+1)); t.top.add(new ScoreRow(player, ms)); } catch(Exception ignored){} } }
                            t.top.sort(java.util.Comparator.comparingLong(s -> s.ms)); if (t.top.size()>10) t.top = new java.util.ArrayList<>(t.top.subList(0,10));
                        }
                    }
                    wd.tracks.put(tname, t);
                }
                worlds.put(wname, wd);
            }
        }

        ConfigurationSection ss = c.getConfigurationSection("signs");
        if (ss != null) for (String world : ss.getKeys(false)) {
            java.util.List<SignBinding> list = new java.util.ArrayList<>();
            ConfigurationSection wsec = ss.getConfigurationSection(world); if (wsec==null) continue;
            for (String idx : wsec.getKeys(false)) {
                ConfigurationSection sb = wsec.getConfigurationSection(idx);
                try {
                    SignMode mode = SignMode.valueOf(sb.getString("mode")); String track=sb.getString("track");
                    int x=sb.getInt("x"), y=sb.getInt("y"), z=sb.getInt("z");
                    list.add(new SignBinding(mode, track, new BlockVec(world, x, y, z)));
                } catch (Exception ignored){}
            }
            signBindings.put(world, list);
        }
    }

    private void saveToConfig(){
        FileConfiguration c = getConfig();
        c.set("worlds", null);
        for (java.util.Map.Entry<String, WorldData> we : worlds.entrySet()) {
            String wname = we.getKey(); WorldData wd = we.getValue();
            for (Track t : wd.tracks.values()) {
                String base = "worlds." + wname + ".tracks." + t.name;
                c.set(base + ".owner", t.owner == null ? null : t.owner.toString());
                c.set(base + ".mode", t.mode.name());
                c.set(base + ".description", (t.description == null || t.description.isBlank()) ? null : t.description);
                if (t.startLine != null) {
                    c.set(base + ".startLine.x1", t.startLine.getX1()); c.set(base + ".startLine.y1", t.startLine.getY1()); c.set(base + ".startLine.z1", t.startLine.getZ1());
                    c.set(base + ".startLine.x2", t.startLine.getX2()); c.set(base + ".startLine.y2", t.startLine.getY2()); c.set(base + ".startLine.z2", t.startLine.getZ2());
                }
                if (t.finishLine != null) {
                    c.set(base + ".finishLine.x1", t.finishLine.getX1()); c.set(base + ".finishLine.y1", t.finishLine.getY1()); c.set(base + ".finishLine.z1", t.finishLine.getZ1());
                    c.set(base + ".finishLine.x2", t.finishLine.getX2()); c.set(base + ".finishLine.y2", t.finishLine.getY2()); c.set(base + ".finishLine.z2", t.finishLine.getZ2());
                }
                if (t.gate != null) {
                    var b = t.gate.box(); c.set(base + ".gate.minX", b.getMinX()); c.set(base + ".gate.minY", b.getMinY()); c.set(base + ".gate.minZ", b.getMinZ());
                    c.set(base + ".gate.maxX", b.getMaxX()); c.set(base + ".gate.maxY", b.getMaxY()); c.set(base + ".gate.maxZ", b.getMaxZ());
                }
                if (!t.splits.isEmpty()) {
                    int i=0; for (Region r : t.splits) { var b=r.box(); String sb="worlds."+wname+".tracks."+t.name+".splits."+ (i++);
                        c.set(sb + ".minX", b.getMinX()); c.set(sb + ".minY", b.getMinY()); c.set(sb + ".minZ", b.getMinZ());
                        c.set(sb + ".maxX", b.getMaxX()); c.set(sb + ".maxY", b.getMaxY()); c.set(sb + ".maxZ", b.getMaxZ());
                    }
                } else c.set(base + ".splits", null);
                c.set(base + ".laps", t.laps);
                c.set(base + ".best", t.bestMs); c.set(base + ".bestHolder", t.bestHolder);
                c.set(base + ".latest", t.latestMs); c.set(base + ".latestHolder", t.latestHolder);
                java.util.List<String> lines = t.top.stream().map(s -> s.player + ":" + s.ms).collect(Collectors.toList());
                c.set(base + ".leaderboard", lines);
            }
        }
        c.set("signs", null);
        for (java.util.Map.Entry<String, java.util.List<SignBinding>> e : signBindings.entrySet()) {
            String world = e.getKey(); int i=0;
            for (SignBinding sb : e.getValue()) {
                String base = "signs." + world + "." + (i++);
                c.set(base + ".mode", sb.mode.name()); c.set(base + ".track", sb.track);
                c.set(base + ".x", sb.pos.x); c.set(base + ".y", sb.pos.y); c.set(base + ".z", sb.pos.z);
            }
        }
        c.set("displayPrecision", displayPrecision);
        c.set("maxRaceTimeSeconds", (int)(maxRaceMs/1000L));
        saveConfig();
    }

    private static String joinArgs(String[] args, int start){ if (start>=args.length) return ""; StringBuilder sb=new StringBuilder(); for (int i=start;i<args.length;i++){ if (i>start) sb.append(' '); sb.append(args[i]); } return sb.toString().trim(); }
    private static Integer tryParseInt(String s){ try { return Integer.parseInt(s); } catch (Exception e){ return null; } }
}
