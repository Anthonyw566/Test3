package dev.anthonyw.furtherout.fx;

import dev.anthonyw.furtherout.FurtherOut;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import javax.annotation.Nullable;

/**
 * Every sound the mod plays. Most are well-chosen vanilla sounds, so they
 * read instantly (a creeper hiss means "back off"). A few have a softer
 * custom version in the optional pack (tools/make_sounds.py); players without
 * the pack hear the vanilla sound instead, so nothing is ever silent.
 */
public enum Sfx {
    // Danger level changes: a low fade going out, a brighter one coming home.
    DANGER_UP("danger.up", SoundEvents.BEACON_DEACTIVATE, SoundSource.PLAYERS, 0.35f, 0.8f),
    DANGER_DOWN("danger.down", SoundEvents.BEACON_ACTIVATE, SoundSource.PLAYERS, 0.3f, 1.3f),

    MARKED_GAIN("marked.gain", SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.PLAYERS, 0.25f, 1.3f),
    MARKED_PASS("marked.pass", SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.PLAYERS, 0.8f, 1.0f),
    MARKED_AMBUSH(null, SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.HOSTILE, 0.7f, 0.9f),
    MARKED_END("marked.end", SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.4f, 0.8f),

    DOWNED_FALL(null, SoundEvents.GENERIC_BIG_FALL, SoundSource.PLAYERS, 1.0f, 0.8f),
    DOWNED_HEARTBEAT("downed.heartbeat", SoundEvents.NOTE_BLOCK_BASEDRUM, SoundSource.PLAYERS, 0.5f, 0.5f),
    DOWNED_REVIVE("downed.revive", SoundEvents.TOTEM_USE, SoundSource.PLAYERS, 0.35f, 1.4f),

    MIMIC_SPRING(null, SoundEvents.EVOKER_FANGS_ATTACK, SoundSource.HOSTILE, 1.0f, 1.2f),
    KEEPER_SPILL(null, SoundEvents.BUNDLE_DROP_CONTENTS, SoundSource.NEUTRAL, 1.0f, 0.9f),

    WARP(null, SoundEvents.ENDERMAN_TELEPORT, SoundSource.HOSTILE, 1.0f, 0.9f),
    WARP_CHARGED(null, SoundEvents.ENDERMAN_AMBIENT, SoundSource.HOSTILE, 0.6f, 1.4f),
    THIEF_STEAL(null, SoundEvents.WITCH_CELEBRATE, SoundSource.HOSTILE, 0.8f, 1.5f),
    MAGNET_CHARGE(null, SoundEvents.LODESTONE_COMPASS_LOCK, SoundSource.HOSTILE, 1.5f, 0.6f),
    MAGNET_PULL(null, SoundEvents.FISHING_BOBBER_RETRIEVE, SoundSource.HOSTILE, 1.5f, 0.5f),
    VOLATILE_FUSE(null, SoundEvents.CREEPER_PRIMED, SoundSource.HOSTILE, 1.0f, 0.8f),
    WARDED_DEFLECT(null, SoundEvents.SHIELD_BLOCK, SoundSource.HOSTILE, 0.8f, 1.2f);

    @Nullable
    private final Holder<SoundEvent> custom;
    private final Holder<SoundEvent> fallback;
    private final SoundSource source;
    private final float fallbackVolume;
    private final float fallbackPitch;

    Sfx(@Nullable String key, SoundEvent fallback, SoundSource source, float volume, float pitch) {
        this(key, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(fallback), source, volume, pitch);
    }

    Sfx(@Nullable String key, Holder<SoundEvent> fallback, SoundSource source, float volume, float pitch) {
        // Not registered: the client resolves it by name from the resource pack.
        this.custom = key == null ? null : Holder.direct(SoundEvent.createVariableRangeEvent(
                ResourceLocation.fromNamespaceAndPath(FurtherOut.MODID, key)));
        this.fallback = fallback;
        this.source = source;
        this.fallbackVolume = volume;
        this.fallbackPitch = pitch;
    }

    /** The pack's version, or null when the vanilla sound is used for everyone. */
    @Nullable
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
