package dev.courier;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class CourierTest {

    private static YamlConfiguration shipped() {
        var in = CourierTest.class.getResourceAsStream("/config.yml");
        assertNotNull(in);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        y.set("location-picker.biome-blacklist", List.of()); // biomes need a running server
        return y;
    }

    @Test
    void shippedConfigKeepsRewardsAsAListOfMaps() {
        // Config.load needs a running server (items, particles), so this checks the shape it reads
        var rewards = shipped().getMapList("rewards");
        assertEquals(3, rewards.size());
        assertEquals(70, ((Number) rewards.get(0).get("chance")).intValue());
        assertTrue(rewards.get(1).get("items") instanceof List<?> items && items.size() == 1);
        assertTrue(shipped().getString("messages.reload-failed").contains("Reload cancelled"));
    }

    @Test
    void durations() {
        assertEquals(5_400_000L, TimeUtil.parseMillis("1h30m", 0));
        assertEquals(90_000L, TimeUtil.parseMillis("90S", 0));
        assertEquals(7L, TimeUtil.parseMillis("7x", 7));
        assertEquals(7L, TimeUtil.parseMillis(null, 7));
        assertFalse(TimeUtil.valid("1h and a bit"));
        assertEquals("1h 1m 1s", TimeUtil.format(3_661_000L));
    }

    @Test
    void aTierWithNoChanceIsNeverPicked() {
        var never = new Config.RewardEntry(0, 0, List.of(), List.of(), "never");
        var always = new Config.RewardEntry(10, 0, List.of(), List.of(), "always");
        Rewards rewards = new Rewards(() -> null, Logger.getAnonymousLogger());
        for (int i = 0; i < 2000; i++)
            assertSame(always, rewards.pick(List.of(never, always)));
    }
}
