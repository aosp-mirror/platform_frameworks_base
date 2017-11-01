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

import android.annotation.Nullable;
import android.content.Context;
import android.media.AudioPlaybackConfiguration;
import android.media.IAudioService;
import android.media.IPlaybackConfigDispatcher;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.IntArray;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Monitors the state changes of audio players.
 */
class AudioPlayerStateMonitor extends IPlaybackConfigDispatcher.Stub {
    private static boolean DEBUG = MediaSessionService.DEBUG;
    private static String TAG = "AudioPlayerStateMonitor";

    private static AudioPlayerStateMonitor sInstance = new AudioPlayerStateMonitor();

    /**
     * Called when the state of audio player is changed.
     */
    interface OnAudioPlayerStateChangedListener {
        void onAudioPlayerStateChanged(
                int uid, int prevState, @Nullable AudioPlaybackConfiguration config);
    }

    private final static class MessageHandler extends Handler {
        private static final int MSG_AUDIO_PLAYER_STATE_CHANGED = 1;

        private final OnAudioPlayerStateChangedListener mListsner;

        public MessageHandler(Looper looper, OnAudioPlayerStateChangedListener listener) {
            super(looper);
            mListsner = listener;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_AUDIO_PLAYER_STATE_CHANGED:
                    mListsner.onAudioPlayerStateChanged(
                            msg.arg1, msg.arg2, (AudioPlaybackConfiguration) msg.obj);
                    break;
            }
        }

        public void sendAudioPlayerStateChangedMessage(int uid, int prevState,
                AudioPlaybackConfiguration config) {
            obtainMessage(MSG_AUDIO_PLAYER_STATE_CHANGED, uid, prevState, config).sendToTarget();
        }
    }

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Map<OnAudioPlayerStateChangedListener, MessageHandler> mListenerMap =
            new HashMap<>();
    @GuardedBy("mLock")
    private final Map<Integer, Integer> mAudioPlayerStates = new HashMap<>();
    @GuardedBy("mLock")
    private final Map<Integer, HashSet<Integer>> mAudioPlayersForUid = new HashMap<>();
    // Sorted array of UIDs that had active audio playback. (i.e. playing an audio/video)
    // The UID whose audio playback becomes active at the last comes first.
    // TODO(b/35278867): Find and use unique identifier for apps because apps may share the UID.
    @GuardedBy("mLock")
    private final IntArray mSortedAudioPlaybackClientUids = new IntArray();

    @GuardedBy("mLock")
    private boolean mRegisteredToAudioService;

    static AudioPlayerStateMonitor getInstance() {
        return sInstance;
    }

    private AudioPlayerStateMonitor() {
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
            final Map<Integer, Integer> prevAudioPlayerStates = new HashMap<>(mAudioPlayerStates);
            final Map<Integer, HashSet<Integer>> prevAudioPlayersForUid =
                    new HashMap<>(mAudioPlayersForUid);
            synchronized (mLock) {
                mAudioPlayerStates.clear();
                mAudioPlayersForUid.clear();
                for (AudioPlaybackConfiguration config : configs) {
                    int pii = config.getPlayerInterfaceId();
                    int uid = config.getClientUid();
                    mAudioPlayerStates.put(pii, config.getPlayerState());
                    HashSet<Integer> players = mAudioPlayersForUid.get(uid);
                    if (players == null) {
                        players = new HashSet<Integer>();
                        players.add(pii);
                        mAudioPlayersForUid.put(uid, players);
                    } else {
                        players.add(pii);
                    }
                }
                for (AudioPlaybackConfiguration config : configs) {
                    if (!config.isActive()) {
                        continue;
                    }

                    int uid = config.getClientUid();
                    if (!isActiveState(prevAudioPlayerStates.get(config.getPlayerInterfaceId()))) {
                        if (DEBUG) {
                            Log.d(TAG, "Found a new active media playback. " +
                                    AudioPlaybackConfiguration.toLogFriendlyString(config));
                        }
                        // New active audio playback.
                        int index = mSortedAudioPlaybackClientUids.indexOf(uid);
                        if (index == 0) {
                            // It's the lastly played music app already. Skip updating.
                            continue;
                        } else if (index > 0) {
                            mSortedAudioPlaybackClientUids.remove(index);
                        }
                        mSortedAudioPlaybackClientUids.add(0, uid);
                    }
                }
                // Notify the change of audio player states.
                for (AudioPlaybackConfiguration config : configs) {
                    Integer prevState = prevAudioPlayerStates.get(config.getPlayerInterfaceId());
                    if (prevState == null || prevState != config.getPlayerState()) {
                        sendAudioPlayerStateChangedMessageLocked(
                                config.getClientUid(), prevState, config);
                    }
                }
                for (Integer prevUid : prevAudioPlayersForUid.keySet()) {
                    // If all players for prevUid is removed, notify the prev state was
                    // PLAYER_STATE_STARTED only when there were a player whose state was
                    // PLAYER_STATE_STARTED, otherwise any inactive state is okay to notify.
                    if (!mAudioPlayersForUid.containsKey(prevUid)) {
                        Set<Integer> players = mAudioPlayersForUid.get(prevUid);
                        int prevState = AudioPlaybackConfiguration.PLAYER_STATE_UNKNOWN;
                        for (int pii : players) {
                            Integer state = prevAudioPlayerStates.get(pii);
                            if (state == null) {
                                continue;
                            }
                            if (state == AudioPlaybackConfiguration.PLAYER_STATE_STARTED) {
                                prevState = state;
                                break;
                            } else if (prevState
                                    == AudioPlaybackConfiguration.PLAYER_STATE_UNKNOWN) {
                                prevState = state;
                            }
                        }
                        sendAudioPlayerStateChangedMessageLocked(prevUid, prevState, null);
                    }
                }
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Registers OnAudioPlayerStateChangedListener.
     */
    public void registerListener(OnAudioPlayerStateChangedListener listener, Handler handler) {
        synchronized (mLock) {
            mListenerMap.put(listener, new MessageHandler((handler == null) ?
                    Looper.myLooper() : handler.getLooper(), listener));
        }
    }

    /**
     * Unregisters OnAudioPlayerStateChangedListener.
     */
    public void unregisterListener(OnAudioPlayerStateChangedListener listener) {
        synchronized (mLock) {
            mListenerMap.remove(listener);
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
            Set<Integer> players = mAudioPlayersForUid.get(uid);
            if (players == null) {
                return false;
            }
            for (Integer pii : players) {
                if (isActiveState(mAudioPlayerStates.get(pii))) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Cleans up the sorted list of audio playback client UIDs with given {@param
     * mediaButtonSessionUid}.
     * <p>UIDs whose audio playback are inactive and have started before the media button session's
     * audio playback cannot be the lastly played media app. So they won't needed anymore.
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
     * Dumps {@link AudioPlayerStateMonitor}.
     */
    public void dump(Context context, PrintWriter pw, String prefix) {
        synchronized (mLock) {
            pw.println(prefix + "Audio playback (lastly played comes first)");
            String indent = prefix + "  ";
            for (int i = 0; i < mSortedAudioPlaybackClientUids.size(); i++) {
                int uid = mSortedAudioPlaybackClientUids.get(i);
                pw.print(indent + "uid=" + uid + " packages=");
                String[] packages = context.getPackageManager().getPackagesForUid(uid);
                if (packages != null && packages.length > 0) {
                    for (int j = 0; j < packages.length; j++) {
                        pw.print(packages[j] + " ");
                    }
                }
                pw.println();
            }
        }
    }

    public void registerSelfIntoAudioServiceIfNeeded(IAudioService audioService) {
        synchronized (mLock) {
            try {
                if (!mRegisteredToAudioService) {
                    audioService.registerPlaybackCallback(this);
                    mRegisteredToAudioService = true;
                }
            } catch (RemoteException e) {
                Log.wtf(TAG, "Failed to register playback callback", e);
                mRegisteredToAudioService = false;
            }
        }
    }

    private void sendAudioPlayerStateChangedMessageLocked(
            final int uid, final int prevState, final AudioPlaybackConfiguration config) {
        for (MessageHandler messageHandler : mListenerMap.values()) {
            messageHandler.sendAudioPlayerStateChangedMessage(uid, prevState, config);
        }
    }

    private static boolean isActiveState(Integer state) {
        return state != null && state.equals(AudioPlaybackConfiguration.PLAYER_STATE_STARTED);
    }
}
