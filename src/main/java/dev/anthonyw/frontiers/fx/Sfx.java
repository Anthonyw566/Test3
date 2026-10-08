package dev.anthonyw.frontiers.fx;

import dev.anthonyw.frontiers.DistantFrontiers;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Every sound the mod plays. Players with the resource pack hear the custom,
 * synthesized sound (tools/make_sounds.py); everyone else hears the vanilla
 * fallback, so nothing is ever silent.
 */
public enum Sfx {
    RING_DEEPER("ring.deeper", SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.MASTER, 0.35f, 1.6f),
    RING_HOME("ring.home", SoundEvents.PLAYER_LEVELUP, SoundSource.MASTER, 0.5f, 1.4f),
    HEX_CURSE("hex.curse", SoundEvents.WITHER_SPAWN, SoundSource.MASTER, 0.4f, 1.6f),
    HEX_PASS("hex.pass", SoundEvents.WITHER_SPAWN, SoundSource.MASTER, 0.4f, 1.8f),
    HEX_AMBUSH("hex.ambush", SoundEvents.RAID_HORN, SoundSource.HOSTILE, 0.6f, 1.4f),
    HEX_SURVIVE("hex.survive", SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 0.7f, 1.0f),
    DOWNED_FALL("downed.fall", SoundEvents.PLAYER_HURT_SWEET_BERRY_BUSH, SoundSource.PLAYERS, 1.0f, 0.6f),
    DOWNED_REVIVE("downed.revive", SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 0.6f, 1.3f),
    WARPER_WARP("warper.warp", SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0f, 0.8f),
    THIEF_STEAL("thief.steal", SoundEvents.WITCH_CELEBRATE, SoundSource.HOSTILE, 1.0f, 1.4f),
    MAGNETIC_CHARGE("magnetic.charge", SoundEvents.BEACON_ACTIVATE, SoundSource.HOSTILE, 1.2f, 1.6f),
    MAGNETIC_PULSE("magnetic.pulse", SoundEvents.WARDEN_SONIC_BOOM, SoundSource.HOSTILE, 0.6f, 1.8f),
    VOLATILE_FUSE("volatile.fuse", SoundEvents.CREEPER_PRIMED, SoundSource.HOSTILE, 1.5f, 0.6f),
    CHAMPION_SLAIN("elite.champion_slain", SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 0.5f, 0.8f),
    WARDED_DEFLECT("warded.deflect", SoundEvents.SHIELD_BLOCK, SoundSource.HOSTILE, 0.8f, 1.2f);

    private final Holder<SoundEvent> custom;
    private final Holder<SoundEvent> fallback;
    private final SoundSource source;
    private final float fallbackVolume;
    private final float fallbackPitch;

    Sfx(String key, SoundEvent fallback, SoundSource source, float volume, float pitch) {
        this(key, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(fallback), source, volume, pitch);
    }

    Sfx(String key, Holder<SoundEvent> fallback, SoundSource source, float volume, float pitch) {
        // Not registered: the client resolves it by name from the resource pack.
        this.custom = Holder.direct(SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath(DistantFrontiers.MODID, key)));
        this.fallback = fallback;
        this.source = source;
        this.fallbackVolume = volume;
        this.fallbackPitch = pitch;
    }

    public Holder<SoundEvent> custom() {
        return custom;
    }

    public Holder<SoundEvent> fallback() {
        return fallback;
    }

    public SoundSource source() {
        return source;
    }

    public float fallbackVolume() {
        return fallbackVolume;
    }

    public float fallbackPitch() {
        return fallbackPitch;
    }
}
