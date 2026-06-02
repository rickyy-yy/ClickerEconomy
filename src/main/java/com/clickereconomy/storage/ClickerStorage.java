package com.clickereconomy.storage;

import java.util.Map;
import java.util.UUID;

public interface ClickerStorage {

    void init();
    void close();

    Map<UUID, Long> loadClicks();

    /** Persists the full in-memory map. Entries absent from the map are deleted. */
    void saveClicks(Map<UUID, Long> clickCounts);

    /** @return saved multiplier state, or {@code null} if none is stored. */
    MultiplierRecord loadMultiplier();

    void saveMultiplier(double value, long remainingSeconds);

    void clearMultiplier();

    final class MultiplierRecord {
        public final double value;
        public final long   remaining;
        public MultiplierRecord(double value, long remaining) {
            this.value = value;
            this.remaining = remaining;
        }
    }
}
