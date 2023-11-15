/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Class to handle fading out players
 */
public final class FadeOutManager {

    public static final String TAG = "AS.FadeOutManager";

    private static final boolean DEBUG = PlaybackActivityMonitor.DEBUG;

    private final Object mLock = new Object();

    /**
     * Map of uid (key) to faded out apps (value)
     */
    @GuardedBy("mLock")
    private final SparseArray<FadedOutApp> mUidToFadedAppsMap = new SparseArray<>();

    private final FadeConfigurations mFadeConfigurations = new FadeConfigurations();

    /**
     * Sets the custom fade manager configuration to be used for player fade out and in
     *
     * @param fadeManagerConfig custom fade manager configuration
     * @return {@code true} if setting fade manager config succeeded, {@code false} otherwise
     */
    boolean setFadeManagerConfiguration(FadeManagerConfiguration fadeManagerConfig) {
        if (!enableFadeManagerConfiguration()) {
            return false;
        }

        // locked to ensure the fade configs are not updated while faded app state is being updated
        synchronized (mLock) {
            return mFadeConfigurations.setFadeManagerConfiguration(fadeManagerConfig);
        }
    }

    /**
     * Clears the fade manager configuration that was previously set with
     * {@link #setFadeManagerConfiguration(FadeManagerConfiguration)}
     *
     * @return {@code true} if clearing fade manager config succeeded, {@code false} otherwise
     */
    boolean clearFadeManagerConfiguration() {
        if (!enableFadeManagerConfiguration()) {
            return false;
        }

        // locked to ensure the fade configs are not updated while faded app state is being updated
        synchronized (mLock) {
            return mFadeConfigurations.clearFadeManagerConfiguration();
        }
    }

    // TODO explore whether a shorter fade out would be a better UX instead of not fading out at all
    //      (legacy behavior)
    /**
     * Determine whether the focus request would trigger a fade out, given the parameters of the
     * requester and those of the focus loser
     * @param requester the parameters for the focus request
     * @return {@code true} if there can be a fade out over the requester starting to play
     */
    boolean canCauseFadeOut(@NonNull FocusRequester requester, @NonNull FocusRequester loser) {
        if (requester.getAudioAttributes().getContentType() == AudioAttributes.CONTENT_TYPE_SPEECH)
        {
            if (DEBUG) {
                Slog.i(TAG, "not fading out: new focus is for speech");
            }
            return false;
        }
        if ((loser.getGrantFlags() & AudioManager.AUDIOFOCUS_FLAG_PAUSES_ON_DUCKABLE_LOSS) != 0) {
            if (DEBUG) {
                Slog.i(TAG, "not fading out: loser has PAUSES_ON_DUCKABLE_LOSS");
            }
            return false;
        }
        return true;
    }

    /**
     * Evaluates whether the player associated with this configuration can and should be faded out
     * @param apc the configuration of the player
     * @return {@code true} if player type and AudioAttributes are compatible with fade out
     */
    boolean canBeFadedOut(@NonNull AudioPlaybackConfiguration apc) {
        synchronized (mLock) {
            return mFadeConfigurations.isFadeable(apc.getAudioAttributes(), apc.getClientUid(),
                    apc.getPlayerType());
        }
    }

    /**
     * Get the duration to fade-out after losing audio focus
     * @param aa The {@link android.media.AudioAttributes} of the player
     * @return duration in milliseconds
     */
    long getFadeOutDurationOnFocusLossMillis(@NonNull AudioAttributes aa) {
        synchronized (mLock) {
            return mFadeConfigurations.getFadeOutDuration(aa);
        }
    }

    /**
     * Get the delay to fade-in the offending players that do not stop after losing audio focus
     * @param aa The {@link android.media.AudioAttributes}
     * @return duration in milliseconds
     */
    long getFadeInDelayForOffendersMillis(@NonNull AudioAttributes aa) {
        synchronized (mLock) {
            return mFadeConfigurations.getDelayFadeInOffenders(aa);
        }
    }

    void fadeOutUid(int uid, List<AudioPlaybackConfiguration> players) {
        Slog.i(TAG, "fadeOutUid() uid:" + uid);
        synchronized (mLock) {
            if (!mUidToFadedAppsMap.contains(uid)) {
                mUidToFadedAppsMap.put(uid, new FadedOutApp(uid));
            }
            final FadedOutApp fa = mUidToFadedAppsMap.get(uid);
            for (AudioPlaybackConfiguration apc : players) {
                final VolumeShaper.Configuration volShaper =
                        mFadeConfigurations.getFadeOutVolumeShaperConfig(apc.getAudioAttributes());
                fa.addFade(apc, /* skipRamp= */ false, volShaper);
            }
        }
    }

    /**
     * Remove the app for the given UID from the list of faded out apps, unfade out its players
     * @param uid the uid for the app to unfade out
     * @param players map of current available players (so we can get an APC from piid)
     */
    void unfadeOutUid(int uid, Map<Integer, AudioPlaybackConfiguration> players) {
        Slog.i(TAG, "unfadeOutUid() uid:" + uid);
        synchronized (mLock) {
            FadedOutApp fa = mUidToFadedAppsMap.get(uid);
            if (fa == null) {
                return;
            }
            mUidToFadedAppsMap.remove(uid);

            if (!enableFadeManagerConfiguration()) {
                fa.removeUnfadeAll(players);
                return;
            }

            // since fade manager configs may have volume-shaper config per audio attributes,
            // iterate through each palyer and gather respective configs  for fade in
            ArrayList<AudioPlaybackConfiguration> apcs = new ArrayList<>(players.values());
            for (int index = 0; index < apcs.size(); index++) {
                AudioPlaybackConfiguration apc = apcs.get(index);
                VolumeShaper.Configuration config = mFadeConfigurations
                        .getFadeInVolumeShaperConfig(apc.getAudioAttributes());
                fa.fadeInPlayer(apc, config);
            }
            // ideal case all players should be faded in
            fa.clear();
        }
    }

    // pre-condition: apc.getPlayerState() == AudioPlaybackConfiguration.PLAYER_STATE_STARTED
    //   see {@link PlaybackActivityMonitor#playerEvent}
    void checkFade(@NonNull AudioPlaybackConfiguration apc) {
        if (DEBUG) {
            Slog.v(TAG, "checkFade() player piid:"
                    + apc.getPlayerInterfaceId() + " uid:" + apc.getClientUid());
        }

        synchronized (mLock) {
            final VolumeShaper.Configuration volShaper =
                    mFadeConfigurations.getFadeOutVolumeShaperConfig(apc.getAudioAttributes());
            final FadedOutApp fa = mUidToFadedAppsMap.get(apc.getClientUid());
            if (fa == null) {
                return;
            }
            fa.addFade(apc, /* skipRamp= */ true, volShaper);
        }
    }

    /**
     * Remove the player from the list of faded out players because it has been released
     * @param apc the released player
     */
    void removeReleased(@NonNull AudioPlaybackConfiguration apc) {
        final int uid = apc.getClientUid();
        if (DEBUG) {
            Slog.v(TAG, "removedReleased() player piid: "
                    + apc.getPlayerInterfaceId() + " uid:" + uid);
        }
        synchronized (mLock) {
            final FadedOutApp fa = mUidToFadedAppsMap.get(uid);
            if (fa == null) {
                return;
            }
            fa.removeReleased(apc);
        }
    }

    /**
     * Check if uid is currently faded out
     * @param uid Client id
     * @return {@code true} if uid is currently faded out. Othwerwise, {@code false}.
     */
    boolean isUidFadedOut(int uid) {
        synchronized (mLock) {
            return mUidToFadedAppsMap.contains(uid);
        }
    }

    void dump(PrintWriter pw) {
        synchronized (mLock) {
            for (int index = 0; index < mUidToFadedAppsMap.size(); index++) {
                mUidToFadedAppsMap.valueAt(index).dump(pw);
            }
        }
    }

    //=========================================================================
    /**
     * Class to group players from a common app, that are faded out.
     */
    private static final class FadedOutApp {
        private static final VolumeShaper.Operation PLAY_CREATE_IF_NEEDED =
                new VolumeShaper.Operation.Builder(VolumeShaper.Operation.PLAY)
                        .createIfNeeded()
                        .build();

        // like a PLAY_CREATE_IF_NEEDED operation but with a skip to the end of the ramp
        private static final VolumeShaper.Operation PLAY_SKIP_RAMP =
                new VolumeShaper.Operation.Builder(PLAY_CREATE_IF_NEEDED).setXOffset(1.0f).build();

        private final int mUid;
        // key -> piid; value -> volume shaper config applied
        private final SparseArray<VolumeShaper.Configuration> mFadedPlayers = new SparseArray<>();

        FadedOutApp(int uid) {
            mUid = uid;
        }

        void dump(PrintWriter pw) {
            pw.print("\t uid:" + mUid + " piids:");
            for (int index = 0; index < mFadedPlayers.size(); index++) {
                pw.print("piid: " + mFadedPlayers.keyAt(index) + " Volume shaper: "
                        + mFadedPlayers.valueAt(index));
            }
            pw.println("");
        }

        /**
         * Add this player to the list of faded out players and apply the fade
         * @param apc a config that satisfies
         *      apc.getPlayerState() == AudioPlaybackConfiguration.PLAYER_STATE_STARTED
         * @param skipRamp {@code true} if the player should be directly into the end of ramp state.
         *      This value would for instance be {@code false} when adding players at the start
         *      of a fade.
         */
        void addFade(@NonNull AudioPlaybackConfiguration apc, boolean skipRamp,
                @NonNull VolumeShaper.Configuration volShaper) {
            final int piid = Integer.valueOf(apc.getPlayerInterfaceId());

            // positive index return implies player is already faded
            if (mFadedPlayers.indexOfKey(piid) >= 0) {
                if (DEBUG) {
                    Slog.v(TAG, "player piid:" + piid + " already faded out");
                }
                return;
            }
            if (apc.getPlayerProxy() != null) {
                applyVolumeShaperInternal(apc, piid, volShaper,
                        skipRamp ? PLAY_SKIP_RAMP : PLAY_CREATE_IF_NEEDED);
            } else {
                if (DEBUG) {
                    Slog.v(TAG, "Error fading out player piid:" + piid
                            + ", player not found for uid " + mUid);
                }
            }
        }

        void removeUnfadeAll(Map<Integer, AudioPlaybackConfiguration> players) {
            for (int index = 0; index < mFadedPlayers.size(); index++) {
                int piid = mFadedPlayers.keyAt(index);
                final AudioPlaybackConfiguration apc = players.get(piid);
                if ((apc != null) && (apc.getPlayerProxy() != null)) {
                    applyVolumeShaperInternal(apc, piid, /* volShaperConfig= */ null,
                            VolumeShaper.Operation.REVERSE);
                } else {
                    // this piid was in the list of faded players, but wasn't found
                    if (DEBUG) {
                        Slog.v(TAG, "Error unfading out player piid:" + piid
                                + ", player not found for uid " + mUid);
                    }
                }
            }
            mFadedPlayers.clear();
        }

        void fadeInPlayer(@NonNull AudioPlaybackConfiguration apc,
                @Nullable VolumeShaper.Configuration config) {
            int piid = Integer.valueOf(apc.getPlayerInterfaceId());
            // if not found, no need to fade in since it was never faded out
            if (!mFadedPlayers.contains(piid)) {
                if (DEBUG) {
                    Slog.v(TAG, "Player (piid: " + piid + ") for uid (" + mUid
                            + ") is not faded out, no need to fade in");
                }
                return;
            }

            mFadedPlayers.remove(piid);
            if (apc.getPlayerProxy() != null) {
                applyVolumeShaperInternal(apc, piid, config,
                        config != null ? PLAY_CREATE_IF_NEEDED : VolumeShaper.Operation.REVERSE);
            } else {
                if (DEBUG) {
                    Slog.v(TAG, "Error fading in player piid:" + piid
                            + ", player not found for uid " + mUid);
                }
            }
        }

        void clear() {
            if (mFadedPlayers.size() > 0) {
                if (DEBUG) {
                    Slog.v(TAG, "Non empty faded players list being cleared! Faded out players:"
                            + mFadedPlayers);
                }
            }
            // should the players be faded in irrespective?
            mFadedPlayers.clear();
        }

        void removeReleased(@NonNull AudioPlaybackConfiguration apc) {
            mFadedPlayers.delete(Integer.valueOf(apc.getPlayerInterfaceId()));
        }

        private void applyVolumeShaperInternal(AudioPlaybackConfiguration apc, int piid,
                VolumeShaper.Configuration volShaperConfig, VolumeShaper.Operation operation) {
            VolumeShaper.Configuration config = volShaperConfig;
            // when operation is reverse, use the fade out volume shaper config instead
            if (operation.equals(VolumeShaper.Operation.REVERSE)) {
                config = mFadedPlayers.get(piid);
            }
            try {
                PlaybackActivityMonitor.sEventLogger.enqueue(
                        (new PlaybackActivityMonitor.FadeEvent(apc, config, operation))
                                .printLog(TAG));
                apc.getPlayerProxy().applyVolumeShaper(config, operation);
            } catch (Exception e) {
                Slog.e(TAG, "Error fading player piid:" + piid + " uid:" + mUid
                        + " operation:" + operation, e);
            }
        }
    }
}
