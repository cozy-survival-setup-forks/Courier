package dev.courier;

/** Persisted per-player state: cooldown and lifetime counters. The active mission itself is also persisted here. */
final class PlayerData {

    long cooldownUntil;
    int completedTotal;
    int failedTotal;

    // active mission fields, flattened for simple storage; missionId null = no active mission
    String missionId;
    String missionWorld;
    double missionX, missionY, missionZ;
    long missionStartedAt;
    long missionExpiresAt;
    boolean missionResourceWorld;

    /** Set when this player's stored data could not be read: what is in memory is never written over it. */
    volatile boolean unsafe;

    void copyFrom(PlayerData o) {
        cooldownUntil = o.cooldownUntil;
        completedTotal = o.completedTotal;
        failedTotal = o.failedTotal;
        missionId = o.missionId;
        missionWorld = o.missionWorld;
        missionX = o.missionX;
        missionY = o.missionY;
        missionZ = o.missionZ;
        missionStartedAt = o.missionStartedAt;
        missionExpiresAt = o.missionExpiresAt;
        missionResourceWorld = o.missionResourceWorld;
    }

    PlayerData copy() {
        PlayerData c = new PlayerData();
        c.copyFrom(this);
        return c;
    }
}
