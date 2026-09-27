package dev.courier;

/** Persisted per-player state: cooldown and lifetime counters. The active mission itself is also persisted here. */
final class PlayerData {

    long cooldownUntil;
    int completedTotal;
    int failedTotal;

    // active mission fields, flattened for simple YAML storage; missionId null = no active mission
    String missionId;
    String missionWorld;
    double missionX, missionY, missionZ;
    long missionStartedAt;
    long missionExpiresAt;
    boolean missionResourceWorld;
}
