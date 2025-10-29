package com.mboyd.racechrono;

import org.bukkit.Location;
import org.bukkit.World;
import java.util.Objects;

public class LineGate {
    private final String worldName;
    private final int x1, y1, z1;
    private final int x2, y2, z2;

    public LineGate(String worldName, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.worldName = worldName;
        this.x1 = x1; this.y1 = y1; this.z1 = z1;
        this.x2 = x2; this.y2 = y2; this.z2 = z2;
        boolean sameX = x1 == x2;
        boolean sameZ = z1 == z2;
        if (sameX == sameZ) throw new IllegalArgumentException("LineGate must be aligned along X or Z with width = 1 block.");
    }

    public boolean contains(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        if (!Objects.equals(loc.getWorld().getName(), worldName)) return false;
        int minY = Math.min(y1, y2), maxY = Math.max(y1, y2);
        if (loc.getY() < minY || loc.getY() > (maxY + 1.0)) return false;
        if (x1 == x2) {
            int x = x1;
            int minZ = Math.min(z1, z2), maxZ = Math.max(z1, z2);
            double minX = x - 0.5, maxX = x + 0.5;
            return loc.getX() >= minX && loc.getX() <= maxX && loc.getZ() >= (minZ - 0.5) && loc.getZ() <= (maxZ + 0.5);
        } else {
            int z = z1;
            int minX = Math.min(x1, x2), maxX = Math.max(x1, x2);
            double minZ = z - 0.5, maxZ = z + 0.5;
            return loc.getZ() >= minZ && loc.getZ() <= maxZ && loc.getX() >= (minX - 0.5) && loc.getX() <= (maxX + 0.5);
        }
    }

    public String getWorldName() { return worldName; }
    public int getX1() { return x1; } public int getY1() { return y1; } public int getZ1() { return z1; }
    public int getX2() { return x2; } public int getY2() { return y2; } public int getZ2() { return z2; }

    public static LineGate fromTwoPoints(Location a, Location b) {
        if (a.getWorld() == null || b.getWorld() == null || !a.getWorld().equals(b.getWorld())) {
            throw new IllegalArgumentException("LineGate endpoints must be in same world.");
        }
        return new LineGate(a.getWorld().getName(), a.getBlockX(), a.getBlockY(), a.getBlockZ(),
                b.getBlockX(), b.getBlockY(), b.getBlockZ());
    }
}
