package dev.courier;

import java.util.UUID;

/** One player's in-progress delivery. Mutated in place, not immutable - state changes every tick. */
final class Mission {

    final UUID player;
    final UUID id;
    String world;
    double x, y, z;
    final long startedAt;
    long expiresAt;
    final boolean resourceWorld;

    Mission(UUID player, UUID id, String world, double x, double y, double z, long startedAt, long expiresAt, boolean resourceWorld) {
        this.player = player;
        this.id = id;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.startedAt = startedAt;
        this.expiresAt = expiresAt;
        this.resourceWorld = resourceWorld;
    }

    boolean expired() {
        return System.currentTimeMillis() >= expiresAt;
    }
}
