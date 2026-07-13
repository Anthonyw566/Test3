package dev.anthonyw.frontiers.core;

import java.util.List;
import java.util.Set;

/** Everything rings.json defines, engine-agnostic. */
public record RingsConfigData(
        boolean useWorldSpawn,
        double originX,
        double originZ,
        List<String> dimensions,
        List<RingDef> rings,
        double maxHealthMult,
        double maxDamageMult,
        double maxSpeedMult,
        Set<String> excludedSpawnTypes,
        List<String> entityBlacklist,
        boolean skipBosses
) {
}
