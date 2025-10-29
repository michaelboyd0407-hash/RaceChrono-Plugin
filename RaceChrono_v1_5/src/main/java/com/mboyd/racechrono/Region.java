package com.mboyd.racechrono;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.util.BoundingBox;
import java.util.Objects;

public class Region {
    private final String worldName;
    private final BoundingBox box;

    public Region(String worldName, BoundingBox box) {
        this.worldName = worldName;
        this.box = box;
    }

    public boolean contains(Location loc) {
        if (loc == null) return false;
        World w = loc.getWorld();
        if (w == null) return false;
        if (!Objects.equals(w.getName(), worldName)) return false;
        return box.contains(loc.toVector());
    }

    public String worldName() { return worldName; }
    public BoundingBox box() { return box; }

    public static Region fromCenterCube(Location c, int r) {
        Location a = c.clone().add(r, r, r);
        Location b = c.clone().add(-r, -r, -r);
        return new Region(c.getWorld().getName(), BoundingBox.of(a, b).expand(0.25));
    }
}
