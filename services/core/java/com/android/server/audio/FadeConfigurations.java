/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.audio;

import static android.media.audiopolicy.Flags.enableFadeManagerConfiguration;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.FadeManagerConfiguration;
import android.media.VolumeShaper;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Class to encapsulate configurations used for fading players
 */
public final class FadeConfigurations {
    public static final String TAG = "AS.FadeConfigurations";

    private static final boolean DEBUG = PlaybackActivityMonitor.DEBUG;


    /** duration of the fade out curve */
    private static final long DEFAULT_FADE_OUT_DURATION_MS = 2000;
    /**
     * delay after which a faded out player will be faded back in. This will be heard by the
     * user only in the case of unmuting players that didn't respect audio focus and didn't
     * stop/pause when their app lost focus.
     * This is the amount of time between the app being notified of
     * the focus loss (when its muted by the fade out), and the time fade in (to unmute) starts
     */
    private static final long DEFAULT_DELAY_FADE_IN_OFFENDERS_MS = 2000;

    private static final List<Integer> DEFAULT_UNFADEABLE_PLAYER_TYPES = List.of(
            AudioPlaybackConfiguration.PLAYER_TYPE_AAUDIO,
            AudioPlaybackConfiguration.PLAYER_TYPE_JAM_SOUNDPOOL
    );

    private static final List<Integer> DEFAULT_UNFADEABLE_CONTENT_TYPES = List.of(
            AudioAttributes.CONTENT_TYPE_SPEECH
    );

    private static final List<Integer> DEFAULT_FADEABLE_USAGES = List.of(
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_MEDIA
    );

    private static final VolumeShaper.Configuration DEFAULT_FADEOUT_VSHAPE =
            new VolumeShaper.Configuration.Builder()
                    .setId(PlaybackActivityMonitor.VOLUME_SHAPER_SYSTEM_FADEOUT_ID)
                    .setCurve(new float[]{0.f, 0.25f, 1.0f} /* times */,
                            new float[]{1.f, 0.65f, 0.0f} /* volumes */)
                    .setOptionFlags(VolumeShaper.Configuration.OPTION_FLAG_CLOCK_TIME)
                    .setDuration(DEFAULT_FADE_OUT_DURATION_MS)
                    .build();

    private static final int INVALID_UID = -1;

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private FadeManagerConfiguration mDefaultFadeManagerConfig;
    @GuardedBy("mLock")
    private FadeManagerConfiguration mUpdatedFadeManagerConfig;
    @GuardedBy("mLock")
    private FadeManagerConfiguration mTransientFadeManagerConfig;
    /** active fade manager is one of: transient > updated > default */
    @GuardedBy("mLock")
    private FadeManagerConfiguration mActiveFadeManagerConfig;

    /**
     * Sets the custom fade manager configuration
     *
     * @param fadeManagerConfig custom fade manager configuration
     * @return {@link AudioManager#SUCCESS}  if setting custom fade manager configuration succeeds
     *     or {@link AudioManager#ERROR} otherwise (example - when fade manager configuration
     *     feature is disabled)
     */
    public int setFadeManagerConfiguration(
            @NonNull FadeManagerConfiguration fadeManagerConfig) {
        if (!enableFadeManagerConfiguration()) {
            return AudioManager.ERROR;
        }

        synchronized (mLock) {
            mUpdatedFadeManagerConfig = Objects.requireNonNull(fadeManagerConfig,
                    "Fade manager configuration cannot be null");
            mActiveFadeManagerConfig = getActiveFadeMgrConfigLocked();
        }
        return AudioManager.SUCCESS;
    }

    /**
     * Clears the fade manager configuration that was previously set with
     * {@link #setFadeManagerConfiguration(FadeManagerConfiguration)}
     *
     * @return {@link AudioManager#SUCCESS}  if previously set fade manager configuration is cleared
     *     or {@link AudioManager#ERROR} otherwise (example, when fade manager configuration feature
     *     is disabled)
     */
    public int clearFadeManagerConfiguration() {
        if (!enableFadeManagerConfiguration()) {
            return AudioManager.ERROR;
        }

        synchronized (mLock) {
            mUpdatedFadeManagerConfig = null;
            mActiveFadeManagerConfig = getActiveFadeMgrConfigLocked();
        }
        return AudioManager.SUCCESS;
    }

    /**
     * Returns the active fade manager configuration
     *
     * @return {@code null} if feature is disabled, or the custom fade manager configuration if set,
     *     or default fade manager configuration if not set.
     */
    @Nullable
    public FadeManagerConfiguration getFadeManagerConfiguration() {
        if (!enableFadeManagerConfiguration()) {
            return null;
        }

        synchronized (mLock) {
            return mActiveFadeManagerConfig;
        }
    }

    /**
     * Sets the transient fade manager configuration
     *
     * @param fadeManagerConfig custom fade manager configuration
     * @return {@link AudioManager#SUCCESS} if setting custom fade manager configuration succeeds
     *     or {@link AudioManager#ERROR} otherwise (example - when fade manager configuration is
     *     disabled)
     */
    public int setTransientFadeManagerConfiguration(
            @NonNull FadeManagerConfiguration fadeManagerConfig) {
        if (!enableFadeManagerConfiguration()) {
            return AudioManager.ERROR;
        }

        synchronized (mLock) {
            mTransientFadeManagerConfig = Objects.requireNonNull(fadeManagerConfig,
                    "Transient FadeManagerConfiguration cannot be null");
            mActiveFadeManagerConfig = getActiveFadeMgrConfigLocked();
        }
        return AudioManager.SUCCESS;
    }

    /**
     * Clears the transient fade manager configuration that was previously set with
     * {@link #setTransientFadeManagerConfiguration(FadeManagerConfiguration)}
     *
     * @return {@link AudioManager#SUCCESS} if previously set transient fade manager configuration
     *     is cleared or {@link AudioManager#ERROR} otherwise (example - when fade manager
     *     configuration is disabled)
     */
    public int clearTransientFadeManagerConfiguration() {
        if (!enableFadeManagerConfiguration()) {
            return AudioManager.ERROR;
        }
        synchronized (mLock) {
            mTransientFadeManagerConfig = null;
            mActiveFadeManagerConfig = getActiveFadeMgrConfigLocked();
        }
        return AudioManager.SUCCESS;
    }

    /**
     * Query if fade should be enforecd on players
     *
     * @return {@code true} if fade is enabled or using default configurations, {@code false}
     * otherwise.
     */
    public boolean isFadeEnabled() {
        if (!enableFadeManagerConfiguration()) {
            return true;
        }

        synchronized (mLock) {
            return getUpdatedFadeManagerConfigLocked().isFadeEnabled();
        }
    }

    /**
     * Query {@link android.media.AudioAttributes.AttributeUsage usages} that are allowed to
     * fade
     *
     * @return list of {@link android.media.AudioAttributes.AttributeUsage}
     */
    @NonNull
    public List<Integer> getFadeableUsages() {
        if (!enableFadeManagerConfiguration()) {
            return DEFAULT_FADEABLE_USAGES;
        }

        synchronized (mLock) {
            FadeManagerConfiguration fadeManagerConfig = getUpdatedFadeManagerConfigLocked();
            // when fade is not enabled, return an empty list instead
            return fadeManagerConfig.isFadeEnabled() ? fadeManagerConfig.getFadeableUsages()
                    : Collections.EMPTY_LIST;
        }
    }

    /**
     * Query {@link android.media.AudioAttributes.AttributeContentType content types} that are
     * exempted from fade enforcement
     *
     * @return list of {@link android.media.AudioAttributes.AttributeContentType}
     */
    @NonNull
    public List<Integer> getUnfadeableContentTypes() {
        if (!enableFadeManagerConfiguration()) {
            return DEFAULT_UNFADEABLE_CONTENT_TYPES;
        }

        synchronized (mLock) {
            FadeManagerConfiguration fadeManagerConfig = getUpdatedFadeManagerConfigLocked();
            // when fade is not enabled, return an empty list instead
            return fadeManagerConfig.isFadeEnabled() ? fadeManagerConfig.getUnfadeableContentTypes()
                    : Collections.EMPTY_LIST;
        }
    }

    /**
     * Query {@link android.media.AudioPlaybackConfiguration.PlayerType player types} that are
     * exempted from fade enforcement
     *
     * @return list of {@link android.media.AudioPlaybackConfiguration.PlayerType}
     */
    @NonNull
    public List<Integer> getUnfadeablePlayerTypes() {
        if (!enableFadeManagerConfiguration()) {
            return DEFAULT_UNFADEABLE_PLAYER_TYPES;
        }

        synchronized (mLock) {
            FadeManagerConfiguration fadeManagerConfig = getUpdatedFadeManagerConfigLocked();
            // when fade is not enabled, return an empty list instead
            return fadeManagerConfig.isFadeEnabled() ? fadeManagerConfig.getUnfadeablePlayerTypes()
                    : Collections.EMPTY_LIST;
        }
    }

    /**
     * Get the {@link android.media.VolumeShaper.Configuration} configuration to be applied
     * for the fade-out
     *
     * @param aa The {@link android.media.AudioAttributes}
     * @return {@link android.media.VolumeShaper.Configuration} for the
     * {@link android.media.AudioAttributes} or default volume shaper if not configured
     */
    @NonNull
    public VolumeShaper.Configuration getFadeOutVolumeShaperConfig(@NonNull AudioAttributes aa) {
        if (!enableFadeManagerConfiguration()) {
            return DEFAULT_FADEOUT_VSHAPE;
        }
        return getOptimalFadeOutVolShaperConfig(aa);
    }

    /**
     * Get the {@link android.media.VolumeShaper.Configuration} configuration to be applied for the
     * fade in
     *
     * @param aa The {@link android.media.AudioAttributes}
     * @return {@link android.media.VolumeShaper.Configuration} for the
     * {@link android.media.AudioAttributes} or {@code null} otherwise
     */
    @Nullable
    public VolumeShaper.Configuration getFadeInVolumeShaperConfig(@NonNull AudioAttributes aa) {
        if (!enableFadeManagerConfiguration()) {
            return null;
        }
        return getOptimalFadeInVolShaperConfig(aa);
    }


    /**
     * Get the duration to fade out a player of type usage
     *
     * @param aa The {@link android.media.AudioAttributes}
     * @return duration in milliseconds for the
     * {@link android.media.AudioAttributes} or default duration if not configured
     */
    public long getFadeOutDuration(@NonNull AudioAttributes aa) {
        if (!isFadeable(aa, INVALID_UID, AudioPlaybackConfiguration.PLAYER_TYPE_UNKNOWN)) {
            return 0;
        }
        if (!enableFadeManagerConfiguration()) {
            return DEFAULT_FADE_OUT_DURATION_MS;
        }
        return getOptimalFadeOutDuration(aa);
    }

    /**
     * Get the delay to fade in offending players that do not stop after losing audio focus
     *
     * @param aa The {@link android.media.AudioAttributes}
     * @return delay in milliseconds for the
     * {@link android.media.AudioAttributes.Attribute} or default delay if not configured
     */
    public long getDelayFadeInOffenders(@NonNull AudioAttributes aa) {
        if (!enableFadeManagerConfiguration()) {
            return DEFAULT_DELAY_FADE_IN_OFFENDERS_MS;
        }

        synchronized (mLock) {
            return getUpdatedFadeManagerConfigLocked().getFadeInDelayForOffenders();
        }
    }

    /**
     * Query {@link android.media.AudioAttributes} that are exempted from fade enforcement
     *
     * @return list of {@link android.media.AudioAttributes}
     */
    @NonNull
    public List<AudioAttributes> getUnfadeableAudioAttributes() {
        // unfadeable audio attributes is only supported with fade manager configurations
        if (!enableFadeManagerConfiguration()) {
            return Collections.EMPTY_LIST;
        }

        synchronized (mLock) {
            FadeManagerConfiguration fadeManagerConfig = getUpdatedFadeManagerConfigLocked();
            // when fade is not enabled, return empty list
            return fadeManagerConfig.isFadeEnabled()
                    ? fadeManagerConfig.getUnfadeableAudioAttributes() : Collections.EMPTY_LIST;
        }
    }

    /**
     * Query uids that are exempted from fade enforcement
     *
     * @return list of uids
     */
    @NonNull
    public List<Integer> getUnfadeableUids() {
        // unfadeable uids is only supported with fade manager configurations
        if (!enableFadeManagerConfiguration()) {
            return Collections.EMPTY_LIST;
        }

        synchronized (mLock) {
            FadeManagerConfiguration fadeManagerConfig = getUpdatedFadeManagerConfigLocked();
            // when fade is not enabled, return empty list
            return fadeManagerConfig.isFadeEnabled() ? fadeManagerConfig.getUnfadeableUids()
                    : Collections.EMPTY_LIST;
        }
    }

    /**
     * Check if it is allowed to fade for the given {@link android.media.AudioAttributes},
     * client uid and {@link android.media.AudioPlaybackConfiguration.PlayerType} config
     *
     * @param aa The {@link android.media.AudioAttributes}
     * @param uid The uid of the client owning the player
     * @param playerType The {@link android.media.AudioPlaybackConfiguration.PlayerType}
     * @return {@code true} if it the config is fadeable and {@code false} otherwise
     */
    public boolean isFadeable(@NonNull AudioAttributes aa, int uid,
            @AudioPlaybackConfiguration.PlayerType int playerType) {
        synchronized (mLock) {
            if (isPlayerTypeUnfadeableLocked(playerType)) {
                if (DEBUG) {
                    Slog.i(TAG, "not fadeable: player type:" + playerType);
                }
                return false;
            }
            if (isContentTypeUnfadeableLocked(aa.getContentType())) {
                if (DEBUG) {
                    Slog.i(TAG, "not fadeable: content type:" + aa.getContentType());
                }
                return false;
            }
            if (!isUsageFadeableLocked(aa.getSystemUsage())) {
                if (DEBUG) {
                    Slog.i(TAG, "not fadeable: usage:" + aa.getUsage());
                }
                return false;
            }
            // new configs using fade manager configuration
            if (isUnfadeableForFadeMgrConfigLocked(aa, uid)) {
                return false;
            }
            return true;
        }
    }

    /** Tries to get the fade out volume shaper config closest to the audio attributes */
    private VolumeShaper.Configuration getOptimalFadeOutVolShaperConfig(AudioAttributes aa) {
        synchronized (mLock) {
            FadeManagerConfiguration fadeManagerConfig = getUpdatedFadeManagerConfigLocked();
            // check if the specific audio attributes has a volume shaper config defined
            VolumeShaper.Configuration volShaperConfig =
                    fadeManagerConfig.getFadeOutVolumeShaperConfigForAudioAttributes(aa);
            if (volShaperConfig != null) {
                return volShaperConfig;
            }

            // get the volume shaper config for usage
            // for fadeable usages, this should never return null
            return fadeManagerConfig.getFadeOutVolumeShaperConfigForUsage(
                    aa.getSystemUsage());
        }
    }

    /** Tries to get the fade in volume shaper config closest to the audio attributes */
    private VolumeShaper.Configuration getOptimalFadeInVolShaperConfig(AudioAttributes aa) {
        synchronized (mLock) {
            FadeManagerConfiguration fadeManagerConfig = getUpdatedFadeManagerConfigLocked();
            // check if the specific audio attributes has a volume shaper config defined
            VolumeShaper.Configuration volShaperConfig =
                    fadeManagerConfig.getFadeInVolumeShaperConfigForAudioAttributes(aa);
            if (volShaperConfig != null) {
                return volShaperConfig;
            }

            // get the volume shaper config for usage
            // for fadeable usages, this should never return null
            return fadeManagerConfig.getFadeInVolumeShaperConfigForUsage(aa.getSystemUsage());
        }
    }

    /** Tries to get the duration closest to the audio attributes */
    private long getOptimalFadeOutDuration(AudioAttributes aa) {
        synchronized (mLock) {
            FadeManagerConfiguration fadeManagerConfig = getUpdatedFadeManagerConfigLocked();
            // check if specific audio attributes has a duration defined
            long duration = fadeManagerConfig.getFadeOutDurationForAudioAttributes(aa);
            if (duration != FadeManagerConfiguration.DURATION_NOT_SET) {
                return duration;
            }

            // get the duration for usage
            // for fadeable usages, this should never return DURATION_NOT_SET
            return fadeManagerConfig.getFadeOutDurationForUsage(aa.getSystemUsage());
        }
    }

    @GuardedBy("mLock")
    private boolean isUnfadeableForFadeMgrConfigLocked(AudioAttributes aa, int uid) {
        if (isAudioAttributesUnfadeableLocked(aa)) {
            if (DEBUG) {
                Slog.i(TAG, "not fadeable: aa:" + aa);
            }
            return true;
        }
        if (isUidUnfadeableLocked(uid)) {
            if (DEBUG) {
                Slog.i(TAG, "not fadeable: uid:" + uid);
            }
            return true;
        }
        return false;
    }

    @GuardedBy("mLock")
    private boolean isUsageFadeableLocked(int usage) {
        if (!enableFadeManagerConfiguration()) {
            return DEFAULT_FADEABLE_USAGES.contains(usage);
        }
        return getUpdatedFadeManagerConfigLocked().isUsageFadeable(usage);
    }

    @GuardedBy("mLock")
    private boolean isContentTypeUnfadeableLocked(int contentType) {
        if (!enableFadeManagerConfiguration()) {
            return DEFAULT_UNFADEABLE_CONTENT_TYPES.contains(contentType);
        }
        return getUpdatedFadeManagerConfigLocked().isContentTypeUnfadeable(contentType);
    }

    @GuardedBy("mLock")
    private boolean isPlayerTypeUnfadeableLocked(int playerType) {
        if (!enableFadeManagerConfiguration()) {
            return DEFAULT_UNFADEABLE_PLAYER_TYPES.contains(playerType);
        }
        return getUpdatedFadeManagerConfigLocked().isPlayerTypeUnfadeable(playerType);
    }

    @GuardedBy("mLock")
    private boolean isAudioAttributesUnfadeableLocked(AudioAttributes aa) {
        if (!enableFadeManagerConfiguration()) {
            // default fade configs do not support unfadeable audio attributes, hence return false
            return false;
        }
        return getUpdatedFadeManagerConfigLocked().isAudioAttributesUnfadeable(aa);
    }

    @GuardedBy("mLock")
    private boolean isUidUnfadeableLocked(int uid) {
        if (!enableFadeManagerConfiguration()) {
            // default fade configs do not support unfadeable uids, hence return false
            return false;
        }
        return getUpdatedFadeManagerConfigLocked().isUidUnfadeable(uid);
    }

    @GuardedBy("mLock")
    private FadeManagerConfiguration getUpdatedFadeManagerConfigLocked() {
        if (mActiveFadeManagerConfig == null) {
            mActiveFadeManagerConfig = getActiveFadeMgrConfigLocked();
        }
        return mActiveFadeManagerConfig;
    }

    /** Priority between fade manager configs: Transient > Updated > Default */
    @GuardedBy("mLock")
    private FadeManagerConfiguration getActiveFadeMgrConfigLocked() {
        // below configs are arranged in the order of priority
        // configs placed higher have higher priority
        if (mTransientFadeManagerConfig != null) {
            return mTransientFadeManagerConfig;
        }

        if (mUpdatedFadeManagerConfig != null) {
            return mUpdatedFadeManagerConfig;
        }

        // default - must be the lowest priority
        return getDefaultFadeManagerConfigLocked();
    }

    @GuardedBy("mLock")
    private FadeManagerConfiguration getDefaultFadeManagerConfigLocked() {
        if (mDefaultFadeManagerConfig == null) {
            mDefaultFadeManagerConfig = new FadeManagerConfiguration.Builder().build();
        }
        return mDefaultFadeManagerConfig;
    }
}
