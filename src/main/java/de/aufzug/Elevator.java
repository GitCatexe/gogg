package de.aufzug;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.type.WallSign;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ein Aufzug mit einer 4x4x4-Kabine (Boden, Waende, Decke, Schild, Tuer).
 * Die Kabine wird als Vorlage (cab) aus der Welt gelesen und beim Fahren mitgenommen.
 * Ursprung der Kabine = (minX, currentY, minZ). currentY ist die Ebene des Kabinenbodens.
 */
public final class Elevator {

    public record Floor(String label, int y) {}

    public record Cell(int dx, int dy, int dz, BlockData data) {}

    final AufzugPlugin plugin;
    final String name;
    final World world;
    final int minX, minZ;
    /** Index = dx + 4 * (dz + 4 * dy) */
    final BlockData[] cab;
    final List<Cell> cells = new ArrayList<>();
    final List<Cell> signCells = new ArrayList<>();
    final List<Cell> doorCells = new ArrayList<>();
    final List<Floor> floors = new ArrayList<>();
    /** "x;y;z" -> Etagen-Label (Rufknoepfe an den Etagen, stehen fest) */
    final Map<String, String> buttons = new HashMap<>();
    int currentY;
    /** true, wenn die Kabine als echte Bloecke in der Welt steht */
    boolean cabinReal = true;

    private final Deque<Integer> queue = new ArrayDeque<>();
    private boolean busy;
    private boolean doorsOpen;
    private BukkitTask moveTask;

    // --- Smooth-Fahrt
    private final List<Entity> parts = new ArrayList<>();
    private final List<double[]> partOff = new ArrayList<>(); // dx, dy, dz
    private final List<Float> partYaw = new ArrayList<>();
    private final Map<UUID, Cell> interactions = new HashMap<>();
    private final List<Entity> riders = new ArrayList<>();
    private final Map<UUID, Boolean> prevFlight = new HashMap<>();
    private boolean smoothActive;
    private double smoothY;
    private int smoothTarget;
    private double vel;

    Elevator(AufzugPlugin plugin, String name, World world, int minX, int minZ, BlockData[] cab, int currentY) {
        this.plugin = plugin;
        this.name = name;
        this.world = world;
        this.minX = minX;
        this.minZ = minZ;
        this.cab = cab;
        this.currentY = currentY;
        computeCells();
    }

    static int idx(int dx, int dy, int dz) {
        return dx + 4 * (dz + 4 * dy);
    }

    // ------------------------------------------------------------------ Kabinen-Vorlage

    void computeCells() {
        cells.clear();
        signCells.clear();
        doorCells.clear();
        for (int dy = 0; dy < 4; dy++)
            for (int dz = 0; dz < 4; dz++)
                for (int dx = 0; dx < 4; dx++) {
                    BlockData d = cab[idx(dx, dy, dz)];
                    if (d == null || d.getMaterial().isAir()) continue;
                    boolean door = plugin.isDoorMaterial(d.getMaterial());
                    if (door && d instanceof Openable o) o.setOpen(false);
                    Cell c = new Cell(dx, dy, dz, d);
                    cells.add(c);
                    if (d instanceof WallSign) signCells.add(c);
                    if (door) doorCells.add(c);
                }
    }

    /** Liest die Kabine neu aus der Welt (Kabine muss als echte Bloecke an currentY stehen). */
    void recapture() {
        for (int dy = 0; dy < 4; dy++)
            for (int dz = 0; dz < 4; dz++)
                for (int dx = 0; dx < 4; dx++)
                    cab[idx(dx, dy, dz)] = world.getBlockAt(minX + dx, currentY + dy, minZ + dz).getBlockData().clone();
        computeCells();
    }

    /** Setzt alle Kabinenbloecke an der Position currentY. */
    void placeCabin() {
        for (Cell c : cells)
            world.getBlockAt(minX + c.dx(), currentY + c.dy(), minZ + c.dz()).setBlockData(c.data(), false);
        cabinReal = true;
    }

    private void clearCabin() {
        for (Cell c : cells)
            world.getBlockAt(minX + c.dx(), currentY + c.dy(), minZ + c.dz()).setType(Material.AIR, false);
    }

    Cell signCellAt(Block b) {
        if (!cabinReal || !b.getWorld().equals(world)) return null;
        int dx = b.getX() - minX, dy = b.getY() - currentY, dz = b.getZ() - minZ;
        for (Cell c : signCells) if (c.dx() == dx && c.dy() == dy && c.dz() == dz) return c;
        return null;
    }

    Cell signForInteraction(UUID id) {
        return interactions.get(id);
    }

    // ------------------------------------------------------------------ Etagen

    void sortFloors() {
        floors.sort(Comparator.comparingInt(Floor::y));
    }

    Floor floorByLabel(String label) {
        for (Floor f : floors) if (f.label().equalsIgnoreCase(label)) return f;
        return null;
    }

    Floor floorAtY(int y) {
        for (Floor f : floors) if (f.y() == y) return f;
        return null;
    }

    Floor currentFloor() {
        return cabinReal ? floorAtY(currentY) : null;
    }

    java.util.Set<Integer> queuedYs() {
        return new java.util.HashSet<>(queue);
    }

    boolean isBusy() {
        return busy;
    }

    // ------------------------------------------------------------------ Steuerung

    public void call(int targetY) {
        if (doorsOpen && targetY == currentY && cabinReal) return;
        if (!busy && currentY == targetY) {
            busy = true;
            arrive();
            return;
        }
        if (!queue.contains(targetY)) queue.add(targetY);
        plugin.refreshSigns(this);
        if (!busy) next();
    }

    private void next() {
        Integer target = queue.poll();
        if (target == null) {
            busy = false;
            plugin.refreshSigns(this);
            return;
        }
        busy = true;
        if (target == currentY) {
            arrive();
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> startMove(target), 10L);
    }

    private void fail(String msg) {
        queue.clear();
        busy = false;
        notifyCabin(Component.text(msg, NamedTextColor.RED));
        plugin.refreshSigns(this);
    }

    private void startMove(int target) {
        plugin.refreshSigns(this);
        int blocked = firstBlockedY(target);
        if (blocked != Integer.MIN_VALUE) {
            fail("Aufzug blockiert - Schacht ist bei Y=" + blocked + " nicht frei (4x4 breit)!");
            return;
        }
        if (plugin.smooth()) startSmooth(target);
        else startBlocks(target);
    }

    /** Prueft den ganzen Weg; liefert die erste blockierte Kabinen-Ebene oder Integer.MIN_VALUE. */
    private int firstBlockedY(int target) {
        int lo = Math.min(currentY, target), hi = Math.max(currentY, target);
        for (int y = lo; y <= hi; y++) {
            for (Cell c : cells) {
                int wy = y + c.dy();
                if (wy >= currentY && wy < currentY + 4) continue; // dort steht gerade die eigene Kabine
                if (!world.getBlockAt(minX + c.dx(), wy, minZ + c.dz()).isPassable()) return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    private void stopMove() {
        if (moveTask != null) {
            moveTask.cancel();
            moveTask = null;
        }
    }

    private void arrive() {
        plugin.saveElevators();
        plugin.refreshSigns(this);
        playAt(centerLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, 1.2f, 1.0f);
        openDoors();
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> closeDoorsThen(() -> Bukkit.getScheduler().runTaskLater(plugin, this::next, 10L)),
                plugin.doorOpenTicks());
    }

    // ------------------------------------------------------------------ Fahrt: Bloecke (ruckelig, robust)

    private void startBlocks(int target) {
        moveTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int dir = Integer.compare(target, currentY);
            if (dir == 0) {
                stopMove();
                arrive();
                return;
            }
            if (!stepBlocks(dir)) {
                stopMove();
                fail("Aufzug blockiert!");
                return;
            }
            if (currentY == target) {
                stopMove();
                arrive();
            }
        }, 0L, Math.max(1, plugin.blockTicks()));
    }

    private boolean stepBlocks(int dir) {
        int newY = currentY + dir;
        if (newY < world.getMinHeight() || newY + 4 >= world.getMaxHeight()) return false;
        BoundingBox box = new BoundingBox(minX, currentY, minZ, minX + 4, currentY + 4, minZ + 4);
        Collection<Entity> inside = world.getNearbyEntities(box,
                en -> !(en instanceof Display) && !(en instanceof Interaction));
        clearCabin();
        currentY = newY;
        placeCabin();
        for (Entity e : inside) {
            Location l = e.getLocation();
            l.add(0, dir, 0);
            e.setFallDistance(0f);
            e.teleport(l);
        }
        plugin.refreshSigns(this);
        return true;
    }

    // ------------------------------------------------------------------ Fahrt: smooth (Display-Entities)

    private static double[] textAnchor(BlockFace f) {
        return switch (f) {
            case NORTH -> new double[]{0.5, 0.874, 180};
            case SOUTH -> new double[]{0.5, 0.126, 0};
            case EAST -> new double[]{0.126, 0.5, -90};
            default -> new double[]{0.874, 0.5, 90};
        };
    }

    private void addPart(Entity e, double dx, double dy, double dz, float yaw) {
        parts.add(e);
        partOff.add(new double[]{dx, dy, dz});
        partYaw.add(yaw);
    }

    private void startSmooth(int target) {
        smoothActive = true;
        smoothTarget = target;
        smoothY = currentY;
        vel = 0;

        // Mitfahrer einsammeln
        riders.clear();
        prevFlight.clear();
        BoundingBox box = new BoundingBox(minX, currentY, minZ, minX + 4, currentY + 4, minZ + 4);
        for (Entity e : world.getNearbyEntities(box, en -> !(en instanceof Display) && !(en instanceof Interaction))) {
            riders.add(e);
            if (e instanceof Player p) {
                prevFlight.put(p.getUniqueId(), p.getAllowFlight());
                p.setAllowFlight(true); // sonst "Flying is not enabled"-Kick bei langer Fahrt
            }
        }

        // Anzeige-Entities (Bloecke)
        for (Cell c : cells) {
            Location loc = new Location(world, minX + c.dx(), currentY + c.dy(), minZ + c.dz());
            BlockDisplay bd = world.spawn(loc, BlockDisplay.class, d -> {
                d.setBlock(c.data());
                d.setTeleportDuration(2);
                d.setPersistent(false);
            });
            addPart(bd, c.dx(), c.dy(), c.dz(), 0f);
        }

        // Schilder: Text + klickbare Flaeche
        Component text = plugin.signText(this);
        for (Cell sc : signCells) {
            BlockFace f = ((WallSign) sc.data()).getFacing();
            double[] a = textAnchor(f);
            double oy = 0.53125 + plugin.textOffsetY();
            Location tl = new Location(world, minX + sc.dx() + a[0], currentY + sc.dy() + oy, minZ + sc.dz() + a[1],
                    (float) a[2], 0f);
            TextDisplay td = world.spawn(tl, TextDisplay.class, d -> {
                d.text(text);
                d.setBillboard(Display.Billboard.FIXED);
                d.setAlignment(TextDisplay.TextAlignment.CENTER);
                d.setDefaultBackground(false);
                d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                d.setLineWidth(400);
                d.setTransformation(new Transformation(new Vector3f(), new AxisAngle4f(),
                        new Vector3f(0.5f, 0.5f, 0.5f), new AxisAngle4f()));
                d.setTeleportDuration(2);
                d.setPersistent(false);
            });
            addPart(td, sc.dx() + a[0], sc.dy() + oy, sc.dz() + a[1], (float) a[2]);

            Location il = new Location(world, minX + sc.dx() + 0.5, currentY + sc.dy() + 0.28125, minZ + sc.dz() + 0.5);
            Interaction in = world.spawn(il, Interaction.class, d -> {
                d.setInteractionWidth(1.0f);
                d.setInteractionHeight(0.5f);
                d.setResponsive(true);
                d.setPersistent(false);
            });
            addPart(in, sc.dx() + 0.5, sc.dy() + 0.28125, sc.dz() + 0.5, 0f);
            interactions.put(in.getUniqueId(), sc);
        }

        // echte Bloecke wegnehmen
        clearCabin();
        cabinReal = false;

        moveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickSmooth, 1L, 1L);
    }

    private void tickSmooth() {
        double remaining = Math.abs(smoothTarget - smoothY);
        int dir = smoothTarget > smoothY ? 1 : -1;
        double accel = plugin.acceleration();
        vel = Math.min(plugin.maxSpeed(), vel + accel);
        double brake = Math.sqrt(2 * accel * remaining);
        double move = Math.max(0.04, Math.min(vel, brake));
        boolean arrived = remaining - move <= 1e-6;
        if (arrived) move = remaining;
        smoothY = arrived ? smoothTarget : smoothY + dir * move;

        for (int i = 0; i < parts.size(); i++) {
            double[] o = partOff.get(i);
            Entity part = parts.get(i);
            if (!part.isValid()) continue;
            part.teleport(new Location(world, minX + o[0], smoothY + o[1], minZ + o[2], partYaw.get(i), 0f));
        }

        for (Entity e : riders) {
            if (!e.isValid()) continue;
            Vector v = e.getVelocity();
            v.setY(dir * move);
            e.setVelocity(v);
            e.setFallDistance(0f);
            if (e instanceof Player) {
                Location l = e.getLocation();
                double x = Math.max(minX + 1.3, Math.min(minX + 2.7, l.getX()));
                double z = Math.max(minZ + 1.3, Math.min(minZ + 2.7, l.getZ()));
                if (Math.abs(x - l.getX()) > 0.01 || Math.abs(z - l.getZ()) > 0.01) {
                    l.setX(x);
                    l.setZ(z);
                    e.teleport(l);
                }
            }
        }

        if (arrived) {
            stopMove();
            finishSmooth(smoothTarget);
            arrive();
        }
    }

    private void finishSmooth(int target) {
        stopMove();
        for (Entity part : parts) part.remove();
        parts.clear();
        partOff.clear();
        partYaw.clear();
        interactions.clear();

        currentY = target;
        placeCabin();
        plugin.refreshSigns(this);

        for (Entity e : riders) {
            if (!e.isValid()) continue;
            Location l = e.getLocation();
            double off = Math.max(0, Math.min(1.5, l.getY() - (target + 1)));
            l.setY(target + 1 + off);
            e.setVelocity(new Vector(0, 0, 0));
            e.setFallDistance(0f);
            e.teleport(l);
            if (e instanceof Player p) {
                Boolean prev = prevFlight.get(p.getUniqueId());
                if (prev != null && !prev) {
                    p.setFlying(false);
                    p.setAllowFlight(false);
                }
            }
        }
        riders.clear();
        prevFlight.clear();
        smoothActive = false;
    }

    private void notifyCabin(Component msg) {
        BoundingBox box = new BoundingBox(minX - 1, currentY, minZ - 1, minX + 5, currentY + 5, minZ + 5);
        for (Entity e : world.getNearbyEntities(box)) if (e instanceof Player p) p.sendMessage(msg);
    }

    // ------------------------------------------------------------------ Tueren (fahren mit der Kabine)

    private BoundingBox doorBox() {
        int x0 = 3, y0 = 3, z0 = 3, x1 = 0, y1 = 0, z1 = 0;
        for (Cell c : doorCells) {
            x0 = Math.min(x0, c.dx());
            y0 = Math.min(y0, c.dy());
            z0 = Math.min(z0, c.dz());
            x1 = Math.max(x1, c.dx());
            y1 = Math.max(y1, c.dy());
            z1 = Math.max(z1, c.dz());
        }
        return new BoundingBox(minX + x0, currentY + y0, minZ + z0, minX + x1 + 1, currentY + y1 + 1, minZ + z1 + 1);
    }

    private void setDoors(boolean open) {
        for (Cell c : doorCells) {
            Block b = world.getBlockAt(minX + c.dx(), currentY + c.dy(), minZ + c.dz());
            if (c.data() instanceof Openable) {
                BlockData d = c.data().clone();
                ((Openable) d).setOpen(open);
                b.setBlockData(d, false);
            } else if (open) {
                b.setType(Material.AIR, false);
            } else {
                b.setBlockData(c.data(), false);
            }
        }
    }

    private void openDoors() {
        doorsOpen = true;
        if (doorCells.isEmpty()) return;
        setDoors(true);
        playAt(centerLocation(), Sound.BLOCK_IRON_DOOR_OPEN, 1f, 1f);
    }

    /** Schliesst erst, wenn niemand (Lichtschranke) in der Tuer steht. */
    private void closeDoorsThen(Runnable after) {
        if (doorCells.isEmpty()) {
            doorsOpen = false;
            after.run();
            return;
        }
        boolean occupied = !world.getNearbyEntities(doorBox(), en -> en instanceof LivingEntity).isEmpty();
        if (occupied) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> closeDoorsThen(after), 10L);
            return;
        }
        setDoors(false);
        doorsOpen = false;
        playAt(centerLocation(), Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 1f);
        after.run();
    }

    // ------------------------------------------------------------------ Sonstiges

    private Location centerLocation() {
        return new Location(world, minX + 2.0, currentY + 2.0, minZ + 2.0);
    }

    private void playAt(Location loc, Sound sound, float vol, float pitch) {
        if (!plugin.soundsEnabled()) return;
        world.playSound(loc, sound, SoundCategory.BLOCKS, vol, pitch);
    }

    void shutdown() {
        stopMove();
        queue.clear();
        busy = false;
        if (smoothActive) finishSmooth((int) Math.round(smoothY));
        if (doorsOpen && cabinReal) setDoors(false);
        doorsOpen = false;
    }
}
