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

import android.annotation.NonNull;
import android.media.AudioAttributes;
import android.media.AudioPlaybackConfiguration;
import android.media.VolumeShaper;
import android.util.Slog;

import java.util.List;

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

    /**
     * Query {@link android.media.AudioAttributes.AttributeUsage usages} that are allowed to
     * fade
     * @return list of {@link android.media.AudioAttributes.AttributeUsage}
     */
    @NonNull
    public List<Integer> getFadeableUsages() {
        return DEFAULT_FADEABLE_USAGES;
    }

    /**
     * Query {@link android.media.AudioAttributes.AttributeContentType content types} that are
     * exempted from fade enforcement
     * @return list of {@link android.media.AudioAttributes.AttributeContentType}
     */
    @NonNull
    public List<Integer> getUnfadeableContentTypes() {
        return DEFAULT_UNFADEABLE_CONTENT_TYPES;
    }

    /**
     * Query {@link android.media.AudioPlaybackConfiguration.PlayerType player types} that are
     * exempted from fade enforcement
     * @return list of {@link android.media.AudioPlaybackConfiguration.PlayerType}
     */
    @NonNull
    public List<Integer> getUnfadeablePlayerTypes() {
        return DEFAULT_UNFADEABLE_PLAYER_TYPES;
    }

    /**
     * Get the {@link android.media.VolumeShaper.Configuration} configuration to be applied
     * for the fade-out
     * @param aa The {@link android.media.AudioAttributes}
     * @return {@link android.media.VolumeShaper.Configuration} for the
     * {@link android.media.AudioAttributes.AttributeUsage} or default volume shaper if not
     * configured
     */
    @NonNull
    public VolumeShaper.Configuration getFadeOutVolumeShaperConfig(@NonNull AudioAttributes aa) {
        return DEFAULT_FADEOUT_VSHAPE;
    }

    /**
     * Get the duration to fade out a player of type usage
     * @param aa The {@link android.media.AudioAttributes}
     * @return duration in milliseconds for the
     * {@link android.media.AudioAttributes} or default duration if not configured
     */
    public long getFadeOutDuration(@NonNull AudioAttributes aa) {
        if (!isFadeable(aa, INVALID_UID, AudioPlaybackConfiguration.PLAYER_TYPE_UNKNOWN)) {
            return 0;
        }
        return DEFAULT_FADE_OUT_DURATION_MS;
    }

    /**
     * Get the delay to fade in offending players that do not stop after losing audio focus.
     * @param aa The {@link android.media.AudioAttributes}
     * @return delay in milliseconds for the
     * {@link android.media.AudioAttributes.Attribute} or default delay if not configured
     */
    public long getDelayFadeInOffenders(@NonNull AudioAttributes aa) {
        return DEFAULT_DELAY_FADE_IN_OFFENDERS_MS;
    }

    /**
     * Check if it is allowed to fade for the given {@link android.media.AudioAttributes},
     * client uid and {@link android.media.AudioPlaybackConfiguration.PlayerType} config.
     * @param aa The {@link android.media.AudioAttributes}
     * @param uid The uid of the client owning the player
     * @param playerType The {@link android.media.AudioPlaybackConfiguration.PlayerType}
     * @return {@code true} if it the config is fadeable and {@code false} otherwise
     */
    public boolean isFadeable(@NonNull AudioAttributes aa, int uid,
            @AudioPlaybackConfiguration.PlayerType int playerType) {
        if (isPlayerTypeUnfadeable(playerType)) {
            if (DEBUG) {
                Slog.i(TAG, "not fadeable: player type:" + playerType);
            }
            return false;
        }
        if (isContentTypeUnfadeable(aa.getContentType())) {
            if (DEBUG) {
                Slog.i(TAG, "not fadeable: content type:" + aa.getContentType());
            }
            return false;
        }
        if (!isUsageFadeable(aa.getUsage())) {
            if (DEBUG) {
                Slog.i(TAG, "not fadeable: usage:" + aa.getUsage());
            }
            return false;
        }
        return true;
    }

    private boolean isUsageFadeable(int usage) {
        return getFadeableUsages().contains(usage);
    }

    private boolean isContentTypeUnfadeable(int contentType) {
        return getUnfadeableContentTypes().contains(contentType);
    }

    private boolean isPlayerTypeUnfadeable(int playerType) {
        return getUnfadeablePlayerTypes().contains(playerType);
    }
}
