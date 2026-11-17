package de.aufzug;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class AufzugPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9+\\-_.]{1,4}");

    private final Map<String, Elevator> elevators = new LinkedHashMap<>();
    /** "welt|x;y;z" -> Aufzug (nur Rufknoepfe) */
    private final Map<String, Elevator> index = new HashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private File dataFile;
    private Set<Material> doorMaterials = new HashSet<>();

    private record Pending(String kind, String elevator, String label) {}

    // ------------------------------------------------------------------ Lifecycle

    @Override
    public void onEnable() {
        saveDefaultConfig();
        dataFile = new File(getDataFolder(), "elevators.yml");
        readDoorMaterials();
        loadElevators();
        Bukkit.getPluginManager().registerEvents(this, this);
        var cmd = getCommand("aufzug");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        for (Elevator el : elevators.values()) el.shutdown();
        saveElevators();
    }

    // Config
    boolean smooth() { return getConfig().getBoolean("smooth", true); }
    double maxSpeed() { return getConfig().getDouble("max-speed", 0.35); }
    double acceleration() { return Math.max(0.005, getConfig().getDouble("acceleration", 0.02)); }
    int blockTicks() { return getConfig().getInt("block-mode-ticks-per-block", 2); }
    double textOffsetY() { return getConfig().getDouble("text-offset-y", 0.0); }
    long doorOpenTicks() { return Math.max(1, getConfig().getInt("door-open-seconds", 6)) * 20L; }
    boolean soundsEnabled() { return getConfig().getBoolean("sounds", true); }
    boolean isDoorMaterial(Material m) { return doorMaterials.contains(m); }

    private void readDoorMaterials() {
        doorMaterials = new HashSet<>();
        List<String> names = getConfig().getStringList("door-materials");
        if (names.isEmpty()) names = List.of("IRON_BARS", "IRON_DOOR");
        for (String n : names) {
            Material m = Material.matchMaterial(n);
            if (m != null) doorMaterials.add(m);
        }
    }

    private int columns(int floorCount) {
        int cols = Math.max(1, getConfig().getInt("sign-columns", 5));
        return Math.max(cols, (int) Math.ceil(floorCount / 4.0));
    }

    // ------------------------------------------------------------------ Persistenz

    private void loadElevators() {
        for (Elevator el : elevators.values()) el.shutdown();
        elevators.clear();
        YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = y.getConfigurationSection("elevators");
        if (root != null) {
            for (String n : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(n);
                if (s == null) continue;
                World w = Bukkit.getWorld(s.getString("world", ""));
                List<String> cl = s.getStringList("cabin");
                if (w == null || cl.size() != 64) {
                    getLogger().warning("Aufzug '" + n + "' uebersprungen (Welt fehlt oder altes Format) - bitte neu mit /aufzug create anlegen.");
                    continue;
                }
                BlockData[] cab = new BlockData[64];
                for (int i = 0; i < 64; i++) cab[i] = Bukkit.createBlockData(cl.get(i));
                Elevator el = new Elevator(this, n, w, s.getInt("minX"), s.getInt("minZ"), cab, s.getInt("currentY"));
                for (String f : s.getStringList("floors")) {
                    String[] p = f.split(":", 2);
                    if (p.length == 2) el.floors.add(new Elevator.Floor(p[1], Integer.parseInt(p[0])));
                }
                for (String b : s.getStringList("buttons")) {
                    String[] p = b.split("\\|", 2);
                    if (p.length == 2) el.buttons.put(p[0], p[1]);
                }
                el.sortFloors();
                el.placeCabin(); // Kabine sicher an ihrer gespeicherten Position herstellen
                elevators.put(n, el);
            }
        }
        rebuildIndex();
        for (Elevator el : elevators.values()) refreshSigns(el);
    }

    void saveElevators() {
        YamlConfiguration y = new YamlConfiguration();
        for (Elevator el : elevators.values()) {
            String p = "elevators." + el.name + ".";
            y.set(p + "world", el.world.getName());
            y.set(p + "minX", el.minX);
            y.set(p + "minZ", el.minZ);
            y.set(p + "currentY", el.currentY);
            List<String> cl = new ArrayList<>();
            for (BlockData d : el.cab) cl.add(d.getAsString());
            y.set(p + "cabin", cl);
            List<String> fl = new ArrayList<>();
            for (Elevator.Floor f : el.floors) fl.add(f.y() + ":" + f.label());
            y.set(p + "floors", fl);
            List<String> bl = new ArrayList<>();
            el.buttons.forEach((k, v) -> bl.add(k + "|" + v));
            y.set(p + "buttons", bl);
        }
        try {
            getDataFolder().mkdirs();
            y.save(dataFile);
        } catch (IOException ex) {
            getLogger().severe("Konnte elevators.yml nicht speichern: " + ex.getMessage());
        }
    }

    private void rebuildIndex() {
        index.clear();
        for (Elevator el : elevators.values())
            for (String k : el.buttons.keySet()) index.put(el.world.getName() + "|" + k, el);
    }

    private static String key(Block b) { return b.getX() + ";" + b.getY() + ";" + b.getZ(); }

    // ------------------------------------------------------------------ Schild

    /** Rendert alle Schilder in der (echt stehenden) Kabine neu. */
    void refreshSigns(Elevator el) {
        if (!el.cabinReal) return;
        for (Elevator.Cell c : el.signCells) {
            Block b = el.world.getBlockAt(el.minX + c.dx(), el.currentY + c.dy(), el.minZ + c.dz());
            if (!Tag.WALL_SIGNS.isTagged(b.getType())) continue;
            renderSign(el, (Sign) b.getState());
        }
    }

    private static int px(char c) {
        return switch (c) {
            case ' ' -> 4;
            case 'i', '!', '.', ',', ':', ';', '|', '\'' -> 3;
            case 'l', 'I', '[', ']', 't' -> 4;
            case 'f', 'k', '(', ')', '*', '<', '>', '"' -> 5;
            default -> 6;
        };
    }

    private static int px(String s) {
        int w = 0;
        for (char c : s.toCharArray()) w += px(c);
        return w;
    }

    /** 4 Zeilen; oberste Etage zuerst. Aktuelle Etage gruen, angeforderte gold. */
    List<Component> signLines(Elevator el) {
        List<Elevator.Floor> desc = new ArrayList<>(el.floors);
        Collections.reverse(desc);
        int n = desc.size();
        int cols = columns(n);
        int cell = 82 / cols;
        Elevator.Floor current = el.currentFloor();
        Set<Integer> queued = el.queuedYs();

        List<Component> lines = new ArrayList<>();
        for (int row = 0; row < 4; row++) {
            Component line = Component.empty();
            for (int col = 0; col < cols; col++) {
                int i = row * cols + col;
                String text = i < n ? desc.get(i).label() : "";
                int tw = px(text);
                int left = (int) Math.round(Math.max(0, (cell - tw) / 2.0) / 4.0);
                int right = Math.max(0, (cell - tw - left * 4) / 4);
                NamedTextColor color = NamedTextColor.BLACK;
                if (i < n) {
                    int y = desc.get(i).y();
                    if (current != null && current.y() == y) color = NamedTextColor.DARK_GREEN;
                    else if (queued.contains(y)) color = NamedTextColor.GOLD;
                }
                line = line.append(Component.text(" ".repeat(left)))
                        .append(Component.text(text, color))
                        .append(Component.text(" ".repeat(right)));
            }
            lines.add(line);
        }
        return lines;
    }

    Component signText(Elevator el) {
        return Component.join(JoinConfiguration.newlines(), signLines(el));
    }

    private void renderSign(Elevator el, Sign sign) {
        List<Component> lines = signLines(el);
        SignSide side = sign.getSide(Side.FRONT);
        for (int i = 0; i < 4; i++) side.line(i, lines.get(i));
        sign.setWaxed(true);
        sign.update(true, false);
    }

    /** u: 0 = links .. 1 = rechts, vTop: 0 = oben .. 1 = unten (auf dem Schildbrett). */
    private Elevator.Floor pickFloor(Elevator el, double u, double vTop) {
        List<Elevator.Floor> desc = new ArrayList<>(el.floors);
        Collections.reverse(desc);
        int cols = columns(desc.size());
        int row = (int) Math.max(0, Math.min(3, Math.floor(vTop * 4.0)));
        double uu = Math.max(0, Math.min(0.9999, (u - 0.03) / 0.94));
        int col = (int) (uu * cols);
        int i = row * cols + col;
        return i < desc.size() ? desc.get(i) : null;
    }

    private void signClicked(Player p, Elevator el, double u, double vTop) {
        if (!p.hasPermission("aufzug.use")) return;
        Elevator.Floor f = pickFloor(el, u, vTop);
        if (f == null) return;
        p.sendActionBar(Component.text("Fahre zu Etage " + f.label(), NamedTextColor.GREEN));
        el.call(f.y());
    }

    // ------------------------------------------------------------------ Events

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block b = e.getClickedBlock();
        if (b == null) return;
        Player p = e.getPlayer();

        Pending pd = pending.remove(p.getUniqueId());
        if (pd != null) {
            e.setCancelled(true);
            handleBinding(p, b, pd);
            return;
        }

        // Rufknopf an einer Etage
        Elevator el = index.get(b.getWorld().getName() + "|" + key(b));
        if (el != null) {
            e.setCancelled(true);
            if (!p.hasPermission("aufzug.use")) return;
            Elevator.Floor f = el.floorByLabel(el.buttons.get(key(b)));
            if (f != null) {
                p.sendActionBar(Component.text("Aufzug wurde gerufen (Etage " + f.label() + ")", NamedTextColor.GREEN));
                el.call(f.y());
            }
            return;
        }

        // Schild in der stehenden Kabine
        for (Elevator elv : elevators.values()) {
            Elevator.Cell sc = elv.signCellAt(b);
            if (sc == null) continue;
            e.setCancelled(true);
            BlockFace facing = ((WallSign) sc.data()).getFacing();
            if (e.getBlockFace() != facing) return;
            Vector pos = e.getClickedPosition();
            if (pos == null) return;
            double u = switch (facing) {
                case NORTH -> 1 - pos.getX();
                case SOUTH -> pos.getX();
                case EAST -> 1 - pos.getZ();
                default -> pos.getZ();
            };
            // Wandschild: Brett von 4.5/16 bis 12.5/16 Blockhoehe
            double vTop = (12.5 / 16.0 - pos.getY()) / (8.0 / 16.0);
            signClicked(p, elv, u, vTop);
            return;
        }
    }

    /** Klick auf das Schild waehrend der Fahrt (Interaction-Entity). */
    @EventHandler
    public void onEntityClick(PlayerInteractAtEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        for (Elevator el : elevators.values()) {
            Elevator.Cell sc = el.signForInteraction(e.getRightClicked().getUniqueId());
            if (sc == null) continue;
            e.setCancelled(true);
            Vector r = e.getClickedPosition();
            BlockFace facing = ((WallSign) sc.data()).getFacing();
            double u = switch (facing) {
                case NORTH -> 0.5 - r.getX();
                case SOUTH -> 0.5 + r.getX();
                case EAST -> 0.5 - r.getZ();
                default -> 0.5 + r.getZ();
            };
            double vTop = 1.0 - r.getY() / 0.5;
            signClicked(e.getPlayer(), el, u, vTop);
            return;
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Elevator el = index.get(b.getWorld().getName() + "|" + key(b));
        if (el == null) return;
        if (!e.getPlayer().hasPermission("aufzug.admin")) {
            e.setCancelled(true);
            return;
        }
        el.buttons.remove(key(b));
        rebuildIndex();
        saveElevators();
        ok(e.getPlayer(), "Rufknopf von Aufzug '" + el.name + "' entfernt.");
    }

    private void handleBinding(Player p, Block b, Pending pd) {
        if (pd.kind().equals("unbind")) {
            Elevator owner = index.get(b.getWorld().getName() + "|" + key(b));
            if (owner == null) {
                err(p, "Dieser Block war kein Rufknopf.");
                return;
            }
            owner.buttons.remove(key(b));
            rebuildIndex();
            saveElevators();
            ok(p, "Rufknopf von '" + owner.name + "' entfernt.");
            return;
        }
        Elevator el = elevators.get(pd.elevator());
        if (el == null) {
            err(p, "Aufzug existiert nicht mehr.");
            return;
        }
        if (!Tag.BUTTONS.isTagged(b.getType())) {
            err(p, "Das ist kein Knopf. Abgebrochen.");
            return;
        }
        el.buttons.put(key(b), pd.label());
        rebuildIndex();
        saveElevators();
        ok(p, "Knopf ist jetzt Rufknopf fuer Etage " + pd.label() + " (" + el.name + ").");
    }

    // ------------------------------------------------------------------ Kommandos

    private void ok(CommandSender s, String m) { s.sendMessage(Component.text(m, NamedTextColor.GREEN)); }
    private void err(CommandSender s, String m) { s.sendMessage(Component.text(m, NamedTextColor.RED)); }
    private void info(CommandSender s, String m) { s.sendMessage(Component.text(m, NamedTextColor.GRAY)); }

    private void help(CommandSender s) {
        info(s, "---- Aufzug ----");
        info(s, "/aufzug create <name>   (fertig gebaute 4x4x4-Kabine, mittig auf die 2x2-Bodenflaeche stellen)");
        info(s, "/aufzug floor add <name> <label> [y]   (auf dem Boden der Etage stehen)");
        info(s, "/aufzug floor remove <name> <label>");
        info(s, "/aufzug button <name> <label>   danach Rufknopf (aussen!) rechtsklicken");
        info(s, "/aufzug unbind   danach Rufknopf rechtsklicken");
        info(s, "/aufzug recapture <name>   Kabine neu einlesen (nach Umbau, Kabine muss stehen)");
        info(s, "/aufzug goto <name> <label> | list | info <name> | delete <name> | reload");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] a) {
        if (a.length == 0) {
            help(sender);
            return true;
        }
        String sub = a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help" -> help(sender);
            case "list" -> {
                if (elevators.isEmpty()) info(sender, "Keine Aufzuege vorhanden.");
                for (Elevator el : elevators.values())
                    info(sender, el.name + " - " + el.floors.size() + " Etagen, " + el.world.getName());
            }
            case "reload" -> {
                reloadConfig();
                readDoorMaterials();
                loadElevators();
                ok(sender, "Neu geladen.");
            }
            case "info" -> {
                Elevator el = need(sender, a, 1);
                if (el == null) return true;
                info(sender, "Aufzug " + el.name + " @ " + el.world.getName() + " (" + el.minX + "," + el.minZ + "), Kabinenboden Y=" + el.currentY);
                StringBuilder sb = new StringBuilder();
                for (Elevator.Floor f : el.floors) sb.append(f.label()).append("(y=").append(f.y()).append(") ");
                info(sender, "Etagen: " + sb);
                info(sender, "Kabine: " + el.cells.size() + " Bloecke, " + el.signCells.size() + " Schild(er), "
                        + el.doorCells.size() + " Tuer-Bloecke; Rufknoepfe: " + el.buttons.size());
            }
            case "create" -> create(sender, a);
            case "recapture" -> {
                Elevator el = need(sender, a, 1);
                if (el == null) return true;
                if (el.isBusy() || !el.cabinReal) { err(sender, "Aufzug faehrt gerade - spaeter nochmal."); return true; }
                el.recapture();
                saveElevators();
                refreshSigns(el);
                ok(sender, "Kabine neu eingelesen: " + el.cells.size() + " Bloecke, " + el.signCells.size() + " Schild(er), " + el.doorCells.size() + " Tuer-Bloecke.");
            }
            case "delete" -> {
                Elevator el = need(sender, a, 1);
                if (el == null) return true;
                el.shutdown();
                elevators.remove(el.name);
                rebuildIndex();
                saveElevators();
                ok(sender, "Aufzug '" + el.name + "' geloescht (Bloecke bleiben stehen).");
            }
            case "floor" -> floor(sender, a);
            case "button" -> {
                Player p = player(sender);
                if (p == null || a.length < 3) { err(sender, "/aufzug button <name> <label>"); return true; }
                Elevator el = need(sender, a, 1);
                if (el == null) return true;
                Elevator.Floor f = el.floorByLabel(a[2]);
                if (f == null) { err(sender, "Etage '" + a[2] + "' gibt es nicht."); return true; }
                pending.put(p.getUniqueId(), new Pending("button", el.name, f.label()));
                ok(sender, "Rechtsklicke jetzt den Knopf fuer Etage " + f.label() + " (ausserhalb der Kabine).");
            }
            case "unbind" -> {
                Player p = player(sender);
                if (p == null) return true;
                pending.put(p.getUniqueId(), new Pending("unbind", "", null));
                ok(sender, "Rechtsklicke den Rufknopf, der entfernt werden soll.");
            }
            case "goto" -> {
                Elevator el = need(sender, a, 1);
                if (el == null) return true;
                Elevator.Floor f = a.length > 2 ? el.floorByLabel(a[2]) : null;
                if (f == null) { err(sender, "Etage nicht gefunden."); return true; }
                el.call(f.y());
                ok(sender, "Aufzug faehrt zu Etage " + f.label());
            }
            default -> help(sender);
        }
        return true;
    }

    private Player player(CommandSender s) {
        if (s instanceof Player p) return p;
        err(s, "Nur im Spiel nutzbar.");
        return null;
    }

    private Elevator need(CommandSender s, String[] a, int i) {
        if (a.length <= i) { err(s, "Aufzugname fehlt."); return null; }
        Elevator el = elevators.get(a[i].toLowerCase(Locale.ROOT));
        if (el == null) err(s, "Aufzug '" + a[i] + "' gibt es nicht.");
        return el;
    }

    private void create(CommandSender s, String[] a) {
        Player p = player(s);
        if (p == null) return;
        if (a.length < 2 || !NAME.matcher(a[1].toLowerCase(Locale.ROOT)).matches()) {
            err(s, "/aufzug create <name>  (a-z, 0-9, _ und -)");
            return;
        }
        String name = a[1].toLowerCase(Locale.ROOT);
        if (elevators.containsKey(name)) { err(s, "Name ist schon vergeben."); return; }
        Location l = p.getLocation();
        // Innenflaeche 2x2: naechste Ecke; Kabine = 1 Block Wand rundherum -> 4x4
        int minX = (int) Math.floor(l.getX() - 0.5) - 1;
        int minZ = (int) Math.floor(l.getZ() - 0.5) - 1;
        int baseY = l.getBlockY() - 1;
        World w = p.getWorld();
        for (int dz = 1; dz <= 2; dz++)
            for (int dx = 1; dx <= 2; dx++) {
                Block b = w.getBlockAt(minX + dx, baseY, minZ + dz);
                if (!b.getType().isSolid()) {
                    err(s, "Unter dir ist keine 2x2-Bodenflaeche. Stell dich mittig in die Kabine (auf den Boden).");
                    return;
                }
            }
        BlockData[] cab = new BlockData[64];
        for (int dy = 0; dy < 4; dy++)
            for (int dz = 0; dz < 4; dz++)
                for (int dx = 0; dx < 4; dx++)
                    cab[Elevator.idx(dx, dy, dz)] = w.getBlockAt(minX + dx, baseY + dy, minZ + dz).getBlockData().clone();
        Elevator el = new Elevator(this, name, w, minX, minZ, cab, baseY);
        elevators.put(name, el);
        saveElevators();
        ok(s, "Aufzug '" + name + "' erstellt: " + el.cells.size() + " Kabinenbloecke, " + el.signCells.size()
                + " Schild(er), " + el.doorCells.size() + " Tuer-Bloecke.");
        if (el.signCells.isEmpty()) info(s, "Hinweis: Kein Wandschild in der Kabine gefunden. Schild setzen und /aufzug recapture " + name);
        info(s, "Als Naechstes: auf jeder Etage /aufzug floor add " + name + " <label>");
    }

    private void floor(CommandSender s, String[] a) {
        if (a.length < 4) { err(s, "/aufzug floor add|remove <name> <label> [y]"); return; }
        Elevator el = need(s, a, 2);
        if (el == null) return;
        String sub = a[1].toLowerCase(Locale.ROOT);
        String lab = a[3];
        if (sub.equals("add")) {
            Player p = player(s);
            if (p == null && a.length < 5) return;
            if (!LABEL.matcher(lab).matches()) { err(s, "Label: 1-4 Zeichen (A-Z, 0-9, + - _ .)"); return; }
            if (el.floorByLabel(lab) != null) { err(s, "Label gibt es schon."); return; }
            int y;
            try {
                y = a.length >= 5 ? Integer.parseInt(a[4]) : p.getLocation().getBlockY() - 1;
            } catch (NumberFormatException ex) { err(s, "y muss eine Zahl sein."); return; }
            if (el.floorAtY(y) != null) { err(s, "Auf dieser Hoehe gibt es schon eine Etage."); return; }
            el.floors.add(new Elevator.Floor(lab, y));
            el.sortFloors();
            saveElevators();
            refreshSigns(el);
            ok(s, "Etage '" + lab + "' bei y=" + y + " hinzugefuegt (" + el.floors.size() + " Etagen).");
        } else if (sub.equals("remove")) {
            Elevator.Floor f = el.floorByLabel(lab);
            if (f == null) { err(s, "Etage nicht gefunden."); return; }
            el.floors.remove(f);
            el.buttons.values().removeIf(v -> v.equalsIgnoreCase(f.label()));
            rebuildIndex();
            saveElevators();
            refreshSigns(el);
            ok(s, "Etage '" + lab + "' entfernt.");
        } else err(s, "/aufzug floor add|remove <name> <label> [y]");
    }

    // ------------------------------------------------------------------ Tab-Completion

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String alias, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            out.addAll(List.of("create", "delete", "list", "info", "floor", "button", "unbind", "recapture", "goto", "reload", "help"));
        } else {
            String sub = a[0].toLowerCase(Locale.ROOT);
            if (a.length == 2 && sub.equals("floor")) {
                out.addAll(List.of("add", "remove"));
            } else if ((a.length == 2 && List.of("delete", "info", "button", "goto", "recapture").contains(sub))
                    || (a.length == 3 && sub.equals("floor"))) {
                out.addAll(elevators.keySet());
            } else {
                String en = sub.equals("floor") ? (a.length > 2 ? a[2] : "") : (a.length > 1 ? a[1] : "");
                Elevator el = elevators.get(en.toLowerCase(Locale.ROOT));
                boolean labelSlot = (a.length == 3 && (sub.equals("button") || sub.equals("goto")))
                        || (a.length == 4 && sub.equals("floor") && a[1].equalsIgnoreCase("remove"));
                if (el != null && labelSlot) el.floors.forEach(f -> out.add(f.label()));
            }
        }
        String last = a[a.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
        return out;
    }
}
