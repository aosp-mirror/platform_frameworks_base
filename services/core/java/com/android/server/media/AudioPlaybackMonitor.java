/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.media;

import android.content.Context;
import android.media.AudioManager.AudioPlaybackCallback;
import android.media.AudioPlaybackConfiguration;
import android.media.IAudioService;
import android.media.IPlaybackConfigDispatcher;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.IntArray;
import android.util.Log;
import android.util.SparseArray;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Monitors changes in audio playback, and notify the newly started audio playback through the
 * {@link OnAudioPlaybackStartedListener} and the activeness change through the
 * {@link OnAudioPlaybackActiveStateListener}.
 */
class AudioPlaybackMonitor extends IPlaybackConfigDispatcher.Stub {
    private static boolean DEBUG = MediaSessionService.DEBUG;
    private static String TAG = "AudioPlaybackMonitor";

    private static AudioPlaybackMonitor sInstance;

    /**
     * Called when audio playback is started for a given UID.
     */
    interface OnAudioPlaybackStartedListener {
        void onAudioPlaybackStarted(int uid);
    }

    /**
     * Called when audio player state is changed.
     */
    interface OnAudioPlayerActiveStateChangedListener {
        void onAudioPlayerActiveStateChanged(int uid, boolean active);
    }

    private final Object mLock = new Object();
    private final Context mContext;
    private final List<OnAudioPlaybackStartedListener> mAudioPlaybackStartedListeners
            = new ArrayList<>();
    private final List<OnAudioPlayerActiveStateChangedListener>
            mAudioPlayerActiveStateChangedListeners = new ArrayList<>();
    private final Map<Integer, Integer> mAudioPlaybackStates = new HashMap<>();
    private final Set<Integer> mActiveAudioPlaybackClientUids = new HashSet<>();

    // Sorted array of UIDs that had active audio playback. (i.e. playing an audio/video)
    // The UID whose audio playback becomes active at the last comes first.
    // TODO(b/35278867): Find and use unique identifier for apps because apps may share the UID.
    private final IntArray mSortedAudioPlaybackClientUids = new IntArray();

    static AudioPlaybackMonitor getInstance(Context context, IAudioService audioService) {
        if (sInstance == null) {
            sInstance = new AudioPlaybackMonitor(context, audioService);
        }
        return sInstance;
    }

    private AudioPlaybackMonitor(Context context, IAudioService audioService) {
        mContext = context;
        try {
            audioService.registerPlaybackCallback(this);
        } catch (RemoteException e) {
            Log.wtf(TAG, "Failed to register playback callback", e);
        }
    }

    /**
     * Called when the {@link AudioPlaybackConfiguration} is updated.
     * <p>If an app starts audio playback, the app's local media session will be the media button
     * session. If the app has multiple media sessions, the playback active local session will be
     * picked.
     *
     * @param configs List of the current audio playback configuration
     */
    @Override
    public void dispatchPlaybackConfigChange(List<AudioPlaybackConfiguration> configs,
            boolean flush) {
        if (flush) {
            Binder.flushPendingCommands();
        }
        final long token = Binder.clearCallingIdentity();
        try {
            List<Integer> newActiveAudioPlaybackClientUids = new ArrayList<>();
            List<OnAudioPlayerActiveStateChangedListener> audioPlayerActiveStateChangedListeners;
            List<OnAudioPlaybackStartedListener> audioPlaybackStartedListeners;
            synchronized (mLock) {
                // Update mActiveAudioPlaybackClientUids and mSortedAudioPlaybackClientUids,
                // and find newly activated audio playbacks.
                mActiveAudioPlaybackClientUids.clear();
                for (AudioPlaybackConfiguration config : configs) {
                    // Ignore inactive (i.e. not playing) or PLAYER_TYPE_JAM_SOUNDPOOL
                    // (i.e. playback from the SoundPool class which is only for sound effects)
                    // playback.
                    // Note that we shouldn't ignore PLAYER_TYPE_UNKNOWN because it might be OEM
                    // specific audio/video players.
                    if (!config.isActive() || config.getPlayerType()
                            == AudioPlaybackConfiguration.PLAYER_TYPE_JAM_SOUNDPOOL) {
                        continue;
                    }

                    mActiveAudioPlaybackClientUids.add(config.getClientUid());
                    Integer oldState = mAudioPlaybackStates.get(config.getPlayerInterfaceId());
                    if (!isActiveState(oldState)) {
                        if (DEBUG) {
                            Log.d(TAG, "Found a new active media playback. " +
                                    AudioPlaybackConfiguration.toLogFriendlyString(config));
                        }
                        // New active audio playback.
                        newActiveAudioPlaybackClientUids.add(config.getClientUid());
                        int index = mSortedAudioPlaybackClientUids.indexOf(config.getClientUid());
                        if (index == 0) {
                            // It's the lastly played music app already. Skip updating.
                            continue;
                        } else if (index > 0) {
                            mSortedAudioPlaybackClientUids.remove(index);
                        }
                        mSortedAudioPlaybackClientUids.add(0, config.getClientUid());
                    }
                }
                audioPlayerActiveStateChangedListeners = new ArrayList<>(
                        mAudioPlayerActiveStateChangedListeners);
                audioPlaybackStartedListeners = new ArrayList<>(mAudioPlaybackStartedListeners);
            }
            // Notify the change of audio playback states.
            for (AudioPlaybackConfiguration config : configs) {
                boolean wasActive = isActiveState(
                        mAudioPlaybackStates.get(config.getPlayerInterfaceId()));
                boolean isActive = config.isActive();
                if (wasActive != isActive) {
                    for (OnAudioPlayerActiveStateChangedListener listener
                            : audioPlayerActiveStateChangedListeners) {
                        listener.onAudioPlayerActiveStateChanged(config.getClientUid(),
                                isActive);
                    }
                }
            }
            // Notify the start of audio playback
            for (int uid : newActiveAudioPlaybackClientUids) {
                for (OnAudioPlaybackStartedListener listener : audioPlaybackStartedListeners) {
                    listener.onAudioPlaybackStarted(uid);
                }
            }
            mAudioPlaybackStates.clear();
            for (AudioPlaybackConfiguration config : configs) {
                mAudioPlaybackStates.put(config.getPlayerInterfaceId(), config.getPlayerState());
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Registers OnAudioPlaybackStartedListener.
     */
    public void registerOnAudioPlaybackStartedListener(OnAudioPlaybackStartedListener listener) {
        synchronized (mLock) {
            mAudioPlaybackStartedListeners.add(listener);
        }
    }

    /**
     * Unregisters OnAudioPlaybackStartedListener.
     */
    public void unregisterOnAudioPlaybackStartedListener(OnAudioPlaybackStartedListener listener) {
        synchronized (mLock) {
            mAudioPlaybackStartedListeners.remove(listener);
        }
    }

    /**
     * Registers OnAudioPlayerActiveStateChangedListener.
     */
    public void registerOnAudioPlayerActiveStateChangedListener(
            OnAudioPlayerActiveStateChangedListener listener) {
        synchronized (mLock) {
            mAudioPlayerActiveStateChangedListeners.add(listener);
        }
    }

    /**
     * Unregisters OnAudioPlayerActiveStateChangedListener.
     */
    public void unregisterOnAudioPlayerActiveStateChangedListener(
            OnAudioPlayerActiveStateChangedListener listener) {
        synchronized (mLock) {
            mAudioPlayerActiveStateChangedListeners.remove(listener);
        }
    }

    /**
     * Returns the sorted list of UIDs that have had active audio playback. (i.e. playing an
     * audio/video) The UID whose audio playback becomes active at the last comes first.
     */
    public IntArray getSortedAudioPlaybackClientUids() {
        IntArray sortedAudioPlaybackClientUids = new IntArray();
        synchronized (mLock) {
            sortedAudioPlaybackClientUids.addAll(mSortedAudioPlaybackClientUids);
        }
        return sortedAudioPlaybackClientUids;
    }

    /**
     * Returns if the audio playback is active for the uid.
     */
    public boolean isPlaybackActive(int uid) {
        synchronized (mLock) {
            return mActiveAudioPlaybackClientUids.contains(uid);
        }
    }

    /**
     * Cleans up the sorted list of audio playback client UIDs with given {@param
     * mediaButtonSessionUid}.
     * <p>UIDs whose audio playback started after the media button session's audio playback
     * cannot be the lastly played media app. So they won't needed anymore.
     *
     * @param mediaButtonSessionUid UID of the media button session.
     */
    public void cleanUpAudioPlaybackUids(int mediaButtonSessionUid) {
        synchronized (mLock) {
            int userId = UserHandle.getUserId(mediaButtonSessionUid);
            for (int i = mSortedAudioPlaybackClientUids.size() - 1; i >= 0; i--) {
                if (mSortedAudioPlaybackClientUids.get(i) == mediaButtonSessionUid) {
                    break;
                }
                int uid = mSortedAudioPlaybackClientUids.get(i);
                if (userId == UserHandle.getUserId(uid) && !isPlaybackActive(uid)) {
                    // Clean up unnecessary UIDs.
                    // It doesn't need to be managed profile aware because it's just to prevent
                    // the list from increasing indefinitely. The media button session updating
                    // shouldn't be affected by cleaning up.
                    mSortedAudioPlaybackClientUids.remove(i);
                }
            }
        }
    }

    /**
     * Dumps {@link AudioPlaybackMonitor}.
     */
    public void dump(PrintWriter pw, String prefix) {
        synchronized (mLock) {
            pw.println(prefix + "Audio playback (lastly played comes first)");
            String indent = prefix + "  ";
            for (int i = 0; i < mSortedAudioPlaybackClientUids.size(); i++) {
                int uid = mSortedAudioPlaybackClientUids.get(i);
                pw.print(indent + "uid=" + uid + " packages=");
                String[] packages = mContext.getPackageManager().getPackagesForUid(uid);
                if (packages != null && packages.length > 0) {
                    for (int j = 0; j < packages.length; j++) {
                        pw.print(packages[j] + " ");
                    }
                }
                pw.println();
            }
        }
    }

    private boolean isActiveState(Integer state) {
        return state != null && state.equals(AudioPlaybackConfiguration.PLAYER_STATE_STARTED);
    }
}
