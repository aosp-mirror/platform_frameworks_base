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
import android.media.AudioPlaybackConfiguration;
import android.media.VolumeShaper;
import android.util.Log;

import com.android.internal.util.ArrayUtils;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;

/**
 * Class to handle fading out players
 */
public final class FadeOutManager {

    public static final String TAG = "AudioService.FadeOutManager";

    /*package*/ static final long FADE_OUT_DURATION_MS = 2500;

    private static final boolean DEBUG = PlaybackActivityMonitor.DEBUG;

    private static final VolumeShaper.Configuration FADEOUT_VSHAPE =
            new VolumeShaper.Configuration.Builder()
                    .setId(PlaybackActivityMonitor.VOLUME_SHAPER_SYSTEM_FADEOUT_ID)
                    .setCurve(new float[]{0.f, 1.0f} /* times */,
                            new float[]{1.f, 0.0f} /* volumes */)
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

    /**
     * Evaluates whether the player associated with this configuration can and should be faded out
     * @param apc the configuration of the player
     * @return true if player type and AudioAttributes are compatible with fade out
     */
    static boolean canBeFadedOut(@NonNull AudioPlaybackConfiguration apc) {
        if (ArrayUtils.contains(UNFADEABLE_PLAYER_TYPES, apc.getPlayerType())) {
            if (DEBUG) { Log.i(TAG, "not fading: player type:" + apc.getPlayerType()); }
            return false;
        }
        if (ArrayUtils.contains(UNFADEABLE_CONTENT_TYPES,
                apc.getAudioAttributes().getContentType())) {
            if (DEBUG) {
                Log.i(TAG, "not fading: content type:"
                        + apc.getAudioAttributes().getContentType());
            }
            return false;
        }
        if (!ArrayUtils.contains(FADEABLE_USAGES, apc.getAudioAttributes().getUsage())) {
            if (DEBUG) {
                Log.i(TAG, "not fading: usage:" + apc.getAudioAttributes().getUsage());
            }
            return false;
        }
        return true;
    }

    static long getFadeOutDurationOnFocusLossMillis(AudioAttributes aa) {
        if (ArrayUtils.contains(UNFADEABLE_CONTENT_TYPES, aa.getContentType())) {
            return 0;
        }
        if (!ArrayUtils.contains(FADEABLE_USAGES, aa.getUsage())) {
            return 0;
        }
        return FADE_OUT_DURATION_MS;
    }

    /**
     * Map of uid (key) to faded out apps (value)
     */
    private final HashMap<Integer, FadedOutApp> mFadedApps = new HashMap<Integer, FadedOutApp>();

    synchronized void fadeOutUid(int uid, ArrayList<AudioPlaybackConfiguration> players) {
        Log.i(TAG, "fadeOutUid() uid:" + uid);
        if (!mFadedApps.containsKey(uid)) {
            mFadedApps.put(uid, new FadedOutApp(uid));
        }
        final FadedOutApp fa = mFadedApps.get(uid);
        for (AudioPlaybackConfiguration apc : players) {
            fa.addFade(apc, false /*skipRamp*/);
        }
    }

    synchronized void unfadeOutUid(int uid, HashMap<Integer, AudioPlaybackConfiguration> players) {
        Log.i(TAG, "unfadeOutUid() uid:" + uid);
        final FadedOutApp fa = mFadedApps.remove(uid);
        if (fa == null) {
            return;
        }
        fa.removeUnfadeAll(players);
    }

    synchronized void forgetUid(int uid) {
        //Log.v(TAG, "forget() uid:" + uid);
        //mFadedApps.remove(uid);
        // TODO unfade all players later in case they are reused or the app continued to play
    }

    // pre-condition: apc.getPlayerState() == AudioPlaybackConfiguration.PLAYER_STATE_STARTED
    //   see {@link PlaybackActivityMonitor#playerEvent}
    synchronized void checkFade(@NonNull AudioPlaybackConfiguration apc) {
        if (DEBUG) {
            Log.v(TAG, "checkFade() player piid:"
                    + apc.getPlayerInterfaceId() + " uid:" + apc.getClientUid());
        }
        final FadedOutApp fa = mFadedApps.get(apc.getClientUid());
        if (fa == null) {
            return;
        }
        fa.addFade(apc, true);
    }

    /**
     * Remove the player from the list of faded out players because it has been released
     * @param apc the released player
     */
    synchronized void removeReleased(@NonNull AudioPlaybackConfiguration apc) {
        final int uid = apc.getClientUid();
        if (DEBUG) {
            Log.v(TAG, "removedReleased() player piid: "
                    + apc.getPlayerInterfaceId() + " uid:" + uid);
        }
        final FadedOutApp fa = mFadedApps.get(uid);
        if (fa == null) {
            return;
        }
        fa.removeReleased(apc);
    }

    synchronized void dump(PrintWriter pw) {
        for (FadedOutApp da : mFadedApps.values()) {
            da.dump(pw);
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
                    Log.v(TAG, "player piid:" + piid + " already faded out");
                }
                return;
            }
            try {
                PlaybackActivityMonitor.sEventLogger.log(
                        (new PlaybackActivityMonitor.FadeOutEvent(apc, skipRamp)).printLog(TAG));
                apc.getPlayerProxy().applyVolumeShaper(
                        FADEOUT_VSHAPE,
                        skipRamp ? PLAY_SKIP_RAMP : PLAY_CREATE_IF_NEEDED);
                mFadedPlayers.add(piid);
            } catch (Exception e) {
                Log.e(TAG, "Error fading out player piid:" + piid
                        + " uid:" + apc.getClientUid(), e);
            }
        }

        void removeUnfadeAll(HashMap<Integer, AudioPlaybackConfiguration> players) {
            for (int piid : mFadedPlayers) {
                final AudioPlaybackConfiguration apc = players.get(piid);
                if (apc != null) {
                    try {
                        PlaybackActivityMonitor.sEventLogger.log(
                                (new AudioEventLogger.StringEvent("unfading out piid:"
                                        + piid)).printLog(TAG));
                        apc.getPlayerProxy().applyVolumeShaper(
                                FADEOUT_VSHAPE,
                                VolumeShaper.Operation.REVERSE);
                    } catch (Exception e) {
                        Log.e(TAG, "Error unfading out player piid:" + piid + " uid:" + mUid, e);
                    }
                } else {
                    // this piid was in the list of faded players, but wasn't found
                    if (DEBUG) {
                        Log.v(TAG, "Error unfading out player piid:" + piid
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
