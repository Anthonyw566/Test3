package dev.anthonyw.frontiers.state;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * All persistent server-wide state: the Marks ledger (banked and field),
 * player Heat, daily kill counters, ring charters, first discoveries, the
 * contract board and the active surge. Stored as one SavedData on the
 * overworld so nothing here can be duped, hoppered or lost to a relog.
 */
public final class FrontiersState extends SavedData {
    private static final String NAME = "distantfrontiers";

    private static final SavedData.Factory<FrontiersState> FACTORY =
            new SavedData.Factory<>(FrontiersState::new, FrontiersState::load, null);

    public static FrontiersState get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    // Economy
    private final Map<UUID, Integer> banked = new HashMap<>();
    private final Map<UUID, Integer> field = new HashMap<>();
    private final Map<UUID, Float> heat = new HashMap<>();
    private final Map<UUID, Integer> dailyEliteKills = new HashMap<>();
    private long lastRotationDay = -1;

    // Progression
    private final Set<String> chartered = new LinkedHashSet<>();
    private final Map<String, Integer> charterSlain = new HashMap<>();
    private final Set<String> charterCacheRecovered = new HashSet<>();
    private final Set<String> discovered = new HashSet<>();

    // Contracts
    public final List<Bounty> bounties = new ArrayList<>();
    public final List<Cache> caches = new ArrayList<>();
    private int nextContractId = 1;

    // Surge
    private String surgeRing = "";
    private long surgeUntil = 0;

    public FrontiersState() {
    }

    // ------------------------------------------------------------------
    // Economy

    public int banked(UUID player) {
        return banked.getOrDefault(player, 0);
    }

    public int fieldMarks(UUID player) {
        return field.getOrDefault(player, 0);
    }

    public void addField(UUID player, int amount) {
        if (amount != 0) {
            field.merge(player, amount, Integer::sum);
            setDirty();
        }
    }

    public void addBanked(UUID player, int amount) {
        if (amount != 0) {
            banked.merge(player, amount, Integer::sum);
            setDirty();
        }
    }

    /** Banks all field marks at the given multiplier and returns the banked amount. */
    public int bankField(UUID player, double multiplier) {
        int fieldAmount = fieldMarks(player);
        int amount = (int) Math.round(fieldAmount * multiplier);
        field.remove(player);
        if (amount > 0) {
            banked.merge(player, amount, Integer::sum);
        }
        setDirty();
        return amount;
    }

    public boolean spendBanked(UUID player, int amount) {
        if (banked(player) < amount) {
            return false;
        }
        banked.merge(player, -amount, Integer::sum);
        setDirty();
        return true;
    }

    public float heat(UUID player) {
        return heat.getOrDefault(player, 0f);
    }

    public void setHeat(UUID player, float value) {
        float clamped = Math.max(0f, Math.min(100f, value));
        if (clamped <= 0f) {
            heat.remove(player);
        } else {
            heat.put(player, clamped);
        }
        setDirty();
    }

    /**
     * Registers an elite kill for daily diminishing returns and returns the
     * payout multiplier: full for the first 10 elites each day, half for the
     * next 20, then nothing.
     */
    public double registerEliteKill(UUID player) {
        int kills = dailyEliteKills.merge(player, 1, Integer::sum);
        setDirty();
        if (kills <= 10) {
            return 1.0;
        }
        if (kills <= 30) {
            return 0.5;
        }
        return 0.0;
    }

    /** True when a new in-game day started since the last board rotation. */
    public boolean startNewDayIfNeeded(long day) {
        if (day == lastRotationDay) {
            return false;
        }
        lastRotationDay = day;
        dailyEliteKills.clear();
        setDirty();
        return true;
    }

    // ------------------------------------------------------------------
    // Progression

    public Set<String> chartered() {
        return chartered;
    }

    public boolean isChartered(String ringId) {
        return chartered.contains(ringId);
    }

    public void charter(String ringId) {
        chartered.add(ringId);
        setDirty();
    }

    public int charterSlain(String ringId) {
        return charterSlain.getOrDefault(ringId, 0);
    }

    public void incrementCharterSlain(String ringId) {
        charterSlain.merge(ringId, 1, Integer::sum);
        setDirty();
    }

    public boolean charterCacheRecovered(String ringId) {
        return charterCacheRecovered.contains(ringId);
    }

    public void markCharterCacheRecovered(String ringId) {
        charterCacheRecovered.add(ringId);
        setDirty();
    }

    /** Returns true only for the first discovery of a ring. */
    public boolean discover(String ringId) {
        boolean first = discovered.add(ringId);
        if (first) {
            setDirty();
        }
        return first;
    }

    // ------------------------------------------------------------------
    // Contracts & surge

    public int nextContractId() {
        int id = nextContractId++;
        setDirty();
        return id;
    }

    public String surgeRing() {
        return surgeRing;
    }

    public long surgeUntil() {
        return surgeUntil;
    }

    public void setSurge(String ringId, long until) {
        this.surgeRing = ringId == null ? "" : ringId;
        this.surgeUntil = until;
        setDirty();
    }

    // ------------------------------------------------------------------
    // Serialization

    public static FrontiersState load(CompoundTag tag, HolderLookup.Provider registries) {
        FrontiersState state = new FrontiersState();
        readIntMap(tag.getCompound("banked"), state.banked);
        readIntMap(tag.getCompound("field"), state.field);
        CompoundTag heatTag = tag.getCompound("heat");
        for (String key : heatTag.getAllKeys()) {
            state.heat.put(UUID.fromString(key), heatTag.getFloat(key));
        }
        readIntMap(tag.getCompound("dailyEliteKills"), state.dailyEliteKills);
        state.lastRotationDay = tag.getLong("lastRotationDay");

        readStrings(tag.getList("chartered", Tag.TAG_STRING), state.chartered);
        CompoundTag slain = tag.getCompound("charterSlain");
        for (String key : slain.getAllKeys()) {
            state.charterSlain.put(key, slain.getInt(key));
        }
        readStrings(tag.getList("charterCache", Tag.TAG_STRING), state.charterCacheRecovered);
        readStrings(tag.getList("discovered", Tag.TAG_STRING), state.discovered);

        for (Tag t : tag.getList("bounties", Tag.TAG_COMPOUND)) {
            state.bounties.add(Bounty.load((CompoundTag) t));
        }
        for (Tag t : tag.getList("caches", Tag.TAG_COMPOUND)) {
            state.caches.add(Cache.load((CompoundTag) t));
        }
        state.nextContractId = Math.max(1, tag.getInt("nextContractId"));
        state.surgeRing = tag.getString("surgeRing");
        state.surgeUntil = tag.getLong("surgeUntil");
        return state;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("banked", writeIntMap(banked));
        tag.put("field", writeIntMap(field));
        CompoundTag heatTag = new CompoundTag();
        heat.forEach((uuid, value) -> heatTag.putFloat(uuid.toString(), value));
        tag.put("heat", heatTag);
        tag.put("dailyEliteKills", writeIntMap(dailyEliteKills));
        tag.putLong("lastRotationDay", lastRotationDay);

        tag.put("chartered", writeStrings(chartered));
        CompoundTag slain = new CompoundTag();
        charterSlain.forEach(slain::putInt);
        tag.put("charterSlain", slain);
        tag.put("charterCache", writeStrings(charterCacheRecovered));
        tag.put("discovered", writeStrings(discovered));

        ListTag bountyList = new ListTag();
        bounties.forEach(b -> bountyList.add(b.save()));
        tag.put("bounties", bountyList);
        ListTag cacheList = new ListTag();
        caches.forEach(c -> cacheList.add(c.save()));
        tag.put("caches", cacheList);
        tag.putInt("nextContractId", nextContractId);
        tag.putString("surgeRing", surgeRing);
        tag.putLong("surgeUntil", surgeUntil);
        return tag;
    }

    private static void readIntMap(CompoundTag tag, Map<UUID, Integer> into) {
        for (String key : tag.getAllKeys()) {
            into.put(UUID.fromString(key), tag.getInt(key));
        }
    }

    private static CompoundTag writeIntMap(Map<UUID, Integer> map) {
        CompoundTag tag = new CompoundTag();
        map.forEach((uuid, value) -> tag.putInt(uuid.toString(), value));
        return tag;
    }

    private static void readStrings(ListTag list, Set<String> into) {
        for (Tag t : list) {
            into.add(t.getAsString());
        }
    }

    private static ListTag writeStrings(Set<String> values) {
        ListTag list = new ListTag();
        values.forEach(v -> list.add(StringTag.valueOf(v)));
        return list;
    }

    // ------------------------------------------------------------------

    /** A rotating hunt contract: a named quarry in a target ring. */
    public static final class Bounty {
        public int id;
        public String ringId = "";
        public String mobId = "";
        public String quarryName = "";
        public boolean champion;
        public int reward;
        public final Set<UUID> accepted = new HashSet<>();
        public UUID spawnedEntity; // null until the quarry exists in the world
        public boolean completed;

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("id", id);
            tag.putString("ring", ringId);
            tag.putString("mob", mobId);
            tag.putString("name", quarryName);
            tag.putBoolean("champion", champion);
            tag.putInt("reward", reward);
            tag.putBoolean("completed", completed);
            ListTag acceptedList = new ListTag();
            accepted.forEach(uuid -> acceptedList.add(StringTag.valueOf(uuid.toString())));
            tag.put("accepted", acceptedList);
            if (spawnedEntity != null) {
                tag.putUUID("spawned", spawnedEntity);
            }
            return tag;
        }

        public static Bounty load(CompoundTag tag) {
            Bounty bounty = new Bounty();
            bounty.id = tag.getInt("id");
            bounty.ringId = tag.getString("ring");
            bounty.mobId = tag.getString("mob");
            bounty.quarryName = tag.getString("name");
            bounty.champion = tag.getBoolean("champion");
            bounty.reward = tag.getInt("reward");
            bounty.completed = tag.getBoolean("completed");
            for (Tag t : tag.getList("accepted", Tag.TAG_STRING)) {
                bounty.accepted.add(UUID.fromString(t.getAsString()));
            }
            if (tag.hasUUID("spawned")) {
                bounty.spawnedEntity = tag.getUUID("spawned");
            }
            return bounty;
        }
    }

    /** A supply cache to locate: coordinates in a ring, guarded on arrival. */
    public static final class Cache {
        public int id;
        public String ringId = "";
        public int x;
        public int z;
        public boolean charterCache;
        public int reward;
        public final Set<UUID> accepted = new HashSet<>();
        public boolean placed;
        public int placedX;
        public int placedY;
        public int placedZ;
        public boolean opened;

        public CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("id", id);
            tag.putString("ring", ringId);
            tag.putInt("x", x);
            tag.putInt("z", z);
            tag.putBoolean("charter", charterCache);
            tag.putInt("reward", reward);
            tag.putBoolean("placed", placed);
            tag.putInt("px", placedX);
            tag.putInt("py", placedY);
            tag.putInt("pz", placedZ);
            tag.putBoolean("opened", opened);
            ListTag acceptedList = new ListTag();
            accepted.forEach(uuid -> acceptedList.add(StringTag.valueOf(uuid.toString())));
            tag.put("accepted", acceptedList);
            return tag;
        }

        public static Cache load(CompoundTag tag) {
            Cache cache = new Cache();
            cache.id = tag.getInt("id");
            cache.ringId = tag.getString("ring");
            cache.x = tag.getInt("x");
            cache.z = tag.getInt("z");
            cache.charterCache = tag.getBoolean("charter");
            cache.reward = tag.getInt("reward");
            cache.placed = tag.getBoolean("placed");
            cache.placedX = tag.getInt("px");
            cache.placedY = tag.getInt("py");
            cache.placedZ = tag.getInt("pz");
            cache.opened = tag.getBoolean("opened");
            for (Tag t : tag.getList("accepted", Tag.TAG_STRING)) {
                cache.accepted.add(UUID.fromString(t.getAsString()));
            }
            return cache;
        }
    }
}
