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

import android.annotation.NonNull;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.VolumeShaper;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ArrayUtils;
import com.android.server.utils.EventLogger;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Class to handle fading out players
 */
public final class FadeOutManager {

    public static final String TAG = "AudioService.FadeOutManager";

    /** duration of the fade out curve */
    private static final long FADE_OUT_DURATION_MS = 2000;
    /**
     * delay after which a faded out player will be faded back in. This will be heard by the user
     * only in the case of unmuting players that didn't respect audio focus and didn't stop/pause
     * when their app lost focus.
     * This is the amount of time between the app being notified of
     * the focus loss (when its muted by the fade out), and the time fade in (to unmute) starts
     */
    private static final long DELAY_FADE_IN_OFFENDERS_MS = 2000;

    private static final boolean DEBUG = PlaybackActivityMonitor.DEBUG;

    private static final VolumeShaper.Configuration FADEOUT_VSHAPE =
            new VolumeShaper.Configuration.Builder()
                    .setId(PlaybackActivityMonitor.VOLUME_SHAPER_SYSTEM_FADEOUT_ID)
                    .setCurve(new float[]{0.f, 0.25f, 1.0f} /* times */,
                            new float[]{1.f, 0.65f, 0.0f} /* volumes */)
                    .setOptionFlags(VolumeShaper.Configuration.OPTION_FLAG_CLOCK_TIME)
                    .setDuration(FADE_OUT_DURATION_MS)
                    .build();
    private static final VolumeShaper.Operation PLAY_CREATE_IF_NEEDED =
            new VolumeShaper.Operation.Builder(VolumeShaper.Operation.PLAY)
                    .createIfNeeded()
                    .build();

    private static final int[] UNFADEABLE_PLAYER_TYPES = {
            AudioPlaybackConfiguration.PLAYER_TYPE_AAUDIO,
            AudioPlaybackConfiguration.PLAYER_TYPE_JAM_SOUNDPOOL,
    };

    private static final int[] UNFADEABLE_CONTENT_TYPES = {
            AudioAttributes.CONTENT_TYPE_SPEECH,
    };

    private static final int[] FADEABLE_USAGES = {
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_MEDIA,
    };

    // like a PLAY_CREATE_IF_NEEDED operation but with a skip to the end of the ramp
    private static final VolumeShaper.Operation PLAY_SKIP_RAMP =
            new VolumeShaper.Operation.Builder(PLAY_CREATE_IF_NEEDED).setXOffset(1.0f).build();

    private final Object mLock = new Object();

    /**
     * Map of uid (key) to faded out apps (value)
     */
    @GuardedBy("mLock")
    private final SparseArray<FadedOutApp> mUidToFadedAppsMap = new SparseArray<>();


    // TODO explore whether a shorter fade out would be a better UX instead of not fading out at all
    //      (legacy behavior)
    /**
     * Determine whether the focus request would trigger a fade out, given the parameters of the
     * requester and those of the focus loser
     * @param requester the parameters for the focus request
     * @return true if there can be a fade out over the requester starting to play
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
     * @return true if player type and AudioAttributes are compatible with fade out
     */
    boolean canBeFadedOut(@NonNull AudioPlaybackConfiguration apc) {
        if (ArrayUtils.contains(UNFADEABLE_PLAYER_TYPES, apc.getPlayerType())) {
            if (DEBUG) {
                Slog.i(TAG, "not fading: player type:" + apc.getPlayerType());
            }
            return false;
        }
        if (ArrayUtils.contains(UNFADEABLE_CONTENT_TYPES,
                apc.getAudioAttributes().getContentType())) {
            if (DEBUG) {
                Slog.i(TAG, "not fading: content type:"
                        + apc.getAudioAttributes().getContentType());
            }
            return false;
        }
        if (!ArrayUtils.contains(FADEABLE_USAGES, apc.getAudioAttributes().getUsage())) {
            if (DEBUG) {
                Slog.i(TAG, "not fading: usage:" + apc.getAudioAttributes().getUsage());
            }
            return false;
        }
        return true;
    }

    /**
     * Get the duration to fade-out after losing audio focus
     * @param aa The {@link android.media.AudioAttributes} of the player
     * @return duration in milliseconds
     */
    long getFadeOutDurationOnFocusLossMillis(@NonNull AudioAttributes aa) {
        if (ArrayUtils.contains(UNFADEABLE_CONTENT_TYPES, aa.getContentType())) {
            return 0;
        }
        if (!ArrayUtils.contains(FADEABLE_USAGES, aa.getUsage())) {
            return 0;
        }
        return FADE_OUT_DURATION_MS;
    }

    /**
     * Get the delay to fade-in the offending players that do not stop after losing audio focus
     * @param aa The {@link android.media.AudioAttributes}
     * @return duration in milliseconds
     */
    long getFadeInDelayForOffendersMillis(@NonNull AudioAttributes aa) {
        return DELAY_FADE_IN_OFFENDERS_MS;
    }

    void fadeOutUid(int uid, ArrayList<AudioPlaybackConfiguration> players) {
        Slog.i(TAG, "fadeOutUid() uid:" + uid);
        synchronized (mLock) {
            if (!mUidToFadedAppsMap.contains(uid)) {
                mUidToFadedAppsMap.put(uid, new FadedOutApp(uid));
            }
            final FadedOutApp fa = mUidToFadedAppsMap.get(uid);
            for (AudioPlaybackConfiguration apc : players) {
                fa.addFade(apc, /* skipRamp= */ false);
            }
        }
    }

    /**
     * Remove the app for the given UID from the list of faded out apps, unfade out its players
     * @param uid the uid for the app to unfade out
     * @param players map of current available players (so we can get an APC from piid)
     */
    void unfadeOutUid(int uid, HashMap<Integer, AudioPlaybackConfiguration> players) {
        Slog.i(TAG, "unfadeOutUid() uid:" + uid);
        synchronized (mLock) {
            final FadedOutApp fa = mUidToFadedAppsMap.get(uid);
            if (fa == null) {
                return;
            }
            mUidToFadedAppsMap.remove(uid);
            fa.removeUnfadeAll(players);
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
            final FadedOutApp fa = mUidToFadedAppsMap.get(apc.getClientUid());
            if (fa == null) {
                return;
            }
            fa.addFade(apc, /* skipRamp= */ true);
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
     * @return true if uid is currently faded out. Othwerwise, false.
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
        private final int mUid;
        private final ArrayList<Integer> mFadedPlayers = new ArrayList<Integer>();

        FadedOutApp(int uid) {
            mUid = uid;
        }

        void dump(PrintWriter pw) {
            pw.print("\t uid:" + mUid + " piids:");
            for (int piid : mFadedPlayers) {
                pw.print(" " + piid);
            }
            pw.println("");
        }

        /**
         * Add this player to the list of faded out players and apply the fade
         * @param apc a config that satisfies
         *      apc.getPlayerState() == AudioPlaybackConfiguration.PLAYER_STATE_STARTED
         * @param skipRamp true if the player should be directly into the end of ramp state.
         *      This value would for instance be false when adding players at the start of a fade.
         */
        void addFade(@NonNull AudioPlaybackConfiguration apc, boolean skipRamp) {
            final int piid = new Integer(apc.getPlayerInterfaceId());
            if (mFadedPlayers.contains(piid)) {
                if (DEBUG) {
                    Slog.v(TAG, "player piid:" + piid + " already faded out");
                }
                return;
            }
            try {
                PlaybackActivityMonitor.sEventLogger.enqueue(
                        (new PlaybackActivityMonitor.FadeOutEvent(apc, skipRamp)).printLog(TAG));
                apc.getPlayerProxy().applyVolumeShaper(
                        FADEOUT_VSHAPE,
                        skipRamp ? PLAY_SKIP_RAMP : PLAY_CREATE_IF_NEEDED);
                mFadedPlayers.add(piid);
            } catch (Exception e) {
                Slog.e(TAG, "Error fading out player piid:" + piid
                        + " uid:" + apc.getClientUid(), e);
            }
        }

        void removeUnfadeAll(HashMap<Integer, AudioPlaybackConfiguration> players) {
            for (int piid : mFadedPlayers) {
                final AudioPlaybackConfiguration apc = players.get(piid);
                if (apc != null) {
                    try {
                        PlaybackActivityMonitor.sEventLogger.enqueue(
                                (new EventLogger.StringEvent("unfading out piid:"
                                        + piid)).printLog(TAG));
                        apc.getPlayerProxy().applyVolumeShaper(
                                FADEOUT_VSHAPE,
                                VolumeShaper.Operation.REVERSE);
                    } catch (Exception e) {
                        Slog.e(TAG, "Error unfading out player piid:" + piid + " uid:" + mUid, e);
                    }
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

        void removeReleased(@NonNull AudioPlaybackConfiguration apc) {
            mFadedPlayers.remove(new Integer(apc.getPlayerInterfaceId()));
        }
    }
}
