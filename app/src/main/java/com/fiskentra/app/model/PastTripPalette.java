package com.fiskentra.app.model;

/** Deterministic distinct palette slots for a selected set of at most one palette's worth of trips. */
public final class PastTripPalette {
    private PastTripPalette() { }
    public static int[] slots(long[] ids, int paletteSize) {
        if (paletteSize < 1) throw new IllegalArgumentException("Palette is empty");
        int[] slots = new int[ids.length];
        boolean[] used = new boolean[paletteSize];
        for (int i = 0; i < ids.length; i++) {
            long hash = ids[i] + 0x9e3779b97f4a7c15L;
            hash = (hash ^ (hash >>> 30)) * 0xbf58476d1ce4e5b9L;
            hash = (hash ^ (hash >>> 27)) * 0x94d049bb133111ebL;
            hash ^= hash >>> 31;
            int preferred = Math.floorMod((int) (hash ^ (hash >>> 32)), paletteSize);
            int chosen = preferred;
            for (int probe = 0; probe < paletteSize; probe++) {
                int candidate = (preferred + probe) % paletteSize;
                if (!used[candidate]) { chosen = candidate; break; }
            }
            slots[i] = chosen;
            used[chosen] = true;
        }
        return slots;
    }
}
