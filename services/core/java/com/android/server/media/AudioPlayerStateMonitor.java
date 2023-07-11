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

import android.annotation.NonNull;
import android.content.Context;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Monitors the state changes of audio players.
 */
class AudioPlayerStateMonitor {
    private static final boolean DEBUG = MediaSessionService.DEBUG;
    private static String TAG = "AudioPlayerStateMonitor";

    private static AudioPlayerStateMonitor sInstance;

    /**
     * Listener for handling the active state changes of audio players.
     */
    interface OnAudioPlayerActiveStateChangedListener {
        /**
         * Called when the active state of audio player is changed.
         *
         * @param config The audio playback configuration for the audio player for which active
         *              state was changed. If {@param isRemoved} is {@code true}, this holds
         *              outdated information.
         * @param isRemoved {@code true} if the audio player is removed.
         */
        void onAudioPlayerActiveStateChanged(
                @NonNull AudioPlaybackConfiguration config, boolean isRemoved);
    }

    private final static class MessageHandler extends Handler {
        private static final int MSG_AUDIO_PLAYER_ACTIVE_STATE_CHANGED = 1;

        private final OnAudioPlayerActiveStateChangedListener mListener;

        MessageHandler(Looper looper, OnAudioPlayerActiveStateChangedListener listener) {
            super(looper);
            mListener = listener;
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_AUDIO_PLAYER_ACTIVE_STATE_CHANGED:
                    mListener.onAudioPlayerActiveStateChanged((AudioPlaybackConfiguration) msg.obj,
                            msg.arg1 != 0);
                    break;
            }
        }

        void sendAudioPlayerActiveStateChangedMessage(
                final AudioPlaybackConfiguration config, final boolean isRemoved) {
            obtainMessage(MSG_AUDIO_PLAYER_ACTIVE_STATE_CHANGED,
                    isRemoved ? 1 : 0, 0 /* unused */, config).sendToTarget();
        }
    }

    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final Map<OnAudioPlayerActiveStateChangedListener, MessageHandler> mListenerMap =
            new ArrayMap<>();
    @GuardedBy("mLock")
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final Set<Integer> mActiveAudioUids = new ArraySet<>();
    @GuardedBy("mLock")
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    ArrayMap<Integer, AudioPlaybackConfiguration> mPrevActiveAudioPlaybackConfigs =
            new ArrayMap<>();
    // Sorted array of UIDs that had active audio playback. (i.e. playing an audio/video)
    // The UID whose audio playback becomes active at the last comes first.
    // TODO(b/35278867): Find and use unique identifier for apps because apps may share the UID.
    @GuardedBy("mLock")
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final List<Integer> mSortedAudioPlaybackClientUids = new ArrayList<>();

    static AudioPlayerStateMonitor getInstance(Context context) {
        synchronized (AudioPlayerStateMonitor.class) {
            if (sInstance == null) {
                sInstance = new AudioPlayerStateMonitor(context);
            }
            return sInstance;
        }
    }

    private AudioPlayerStateMonitor(Context context) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        am.registerAudioPlaybackCallback(new AudioManagerPlaybackListener(), null);
    }

    /**
     * Registers OnAudioPlayerActiveStateChangedListener.
     */
    public void registerListener(
            OnAudioPlayerActiveStateChangedListener listener, Handler handler) {
        synchronized (mLock) {
            mListenerMap.put(listener, new MessageHandler((handler == null) ?
                    Looper.myLooper() : handler.getLooper(), listener));
        }
    }

    /**
     * Unregisters OnAudioPlayerActiveStateChangedListener.
     */
    public void unregisterListener(OnAudioPlayerActiveStateChangedListener listener) {
        synchronized (mLock) {
            mListenerMap.remove(listener);
        }
    }

    /**
     * Returns the sorted list of UIDs that have had active audio playback. (i.e. playing an
     * audio/video) The UID whose audio is currently playing comes first, then the UID whose audio
     * playback becomes active at the last comes next.
     */
    public List<Integer> getSortedAudioPlaybackClientUids() {
        List<Integer> sortedAudioPlaybackClientUids = new ArrayList();
        synchronized (mLock) {
            sortedAudioPlaybackClientUids.addAll(mSortedAudioPlaybackClientUids);
        }
        return sortedAudioPlaybackClientUids;
    }

    /**
     * Returns whether the given uid corresponds to the last process to audio or not.
     *
     * <p> This is useful for handling the cases where the foreground app is playing media without
     * a media session. Then, volume events should affect the local music stream rather than other
     * media sessions.
     *
     * @return {@code true} if the given uid corresponds to the last process to audio or
     * {@code false} otherwise.
     */
    public boolean hasUidPlayedAudioLast(int uid) {
        synchronized (mLock) {
            return !mSortedAudioPlaybackClientUids.isEmpty()
                    && uid == mSortedAudioPlaybackClientUids.get(0);
        }
    }

    /**
     * Returns if the audio playback is active for the uid.
     */
    public boolean isPlaybackActive(int uid) {
        synchronized (mLock) {
            return mActiveAudioUids.contains(uid);
        }
    }

    /**
     * Cleans up the sorted list of audio playback client UIDs with given {@param
     * mediaButtonSessionUid}.
     * <p>UIDs whose audio playback are inactive and have started before the media button session's
     * audio playback cannot be the lastly played media app. So they won't be needed anymore.
     *
     * @param mediaButtonSessionUid UID of the media button session.
     */
    public void cleanUpAudioPlaybackUids(int mediaButtonSessionUid) {
        synchronized (mLock) {
            int userId = UserHandle.getUserHandleForUid(mediaButtonSessionUid).getIdentifier();
            for (int i = mSortedAudioPlaybackClientUids.size() - 1; i >= 0; i--) {
                if (mSortedAudioPlaybackClientUids.get(i) == mediaButtonSessionUid) {
                    break;
                }
                int uid = mSortedAudioPlaybackClientUids.get(i);
                if (userId == UserHandle.getUserHandleForUid(uid).getIdentifier()
                        && !isPlaybackActive(uid)) {
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

    @GuardedBy("mLock")
    private void sendAudioPlayerActiveStateChangedMessageLocked(
            final AudioPlaybackConfiguration config, final boolean isRemoved) {
        for (MessageHandler messageHandler : mListenerMap.values()) {
            messageHandler.sendAudioPlayerActiveStateChangedMessage(config, isRemoved);
        }
    }

    private class AudioManagerPlaybackListener extends AudioManager.AudioPlaybackCallback {
        @Override
        public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            synchronized (mLock) {
                // Update mActiveAudioUids
                mActiveAudioUids.clear();
                ArrayMap<Integer, AudioPlaybackConfiguration> activeAudioPlaybackConfigs =
                        new ArrayMap<>();
                for (AudioPlaybackConfiguration config : configs) {
                    if (config.isActive()) {
                        mActiveAudioUids.add(config.getClientUid());
                        activeAudioPlaybackConfigs.put(config.getPlayerInterfaceId(), config);
                    }
                }

                // Update mSortedAudioPlaybackClientUids.
                for (int i = 0; i < activeAudioPlaybackConfigs.size(); ++i) {
                    AudioPlaybackConfiguration config = activeAudioPlaybackConfigs.valueAt(i);
                    final int uid = config.getClientUid();
                    if (!mPrevActiveAudioPlaybackConfigs.containsKey(
                            config.getPlayerInterfaceId())) {
                        if (DEBUG) {
                            Log.d(TAG, "Found a new active media playback. " + config);
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

                if (mActiveAudioUids.size() > 0
                        && !mActiveAudioUids.contains(mSortedAudioPlaybackClientUids.get(0))) {
                    int firstActiveUid = -1;
                    int firstActiveUidIndex = -1;
                    for (int i = 1; i < mSortedAudioPlaybackClientUids.size(); ++i) {
                        int uid = mSortedAudioPlaybackClientUids.get(i);
                        if (mActiveAudioUids.contains(uid)) {
                            firstActiveUidIndex = i;
                            firstActiveUid = uid;
                            break;
                        }
                    }
                    for (int i = firstActiveUidIndex; i > 0; --i) {
                        mSortedAudioPlaybackClientUids.set(i,
                                mSortedAudioPlaybackClientUids.get(i - 1));
                    }
                    mSortedAudioPlaybackClientUids.set(0, firstActiveUid);
                }

                // Notify the active state change of audio players.
                for (AudioPlaybackConfiguration config : configs) {
                    final int pii = config.getPlayerInterfaceId();
                    boolean wasActive = mPrevActiveAudioPlaybackConfigs.remove(pii) != null;
                    if (wasActive != config.isActive()) {
                        sendAudioPlayerActiveStateChangedMessageLocked(
                                config, /* isRemoved */ false);
                    }
                }
                for (AudioPlaybackConfiguration config : mPrevActiveAudioPlaybackConfigs.values()) {
                    sendAudioPlayerActiveStateChangedMessageLocked(config, /* isRemoved */ true);
                }

                // Update mPrevActiveAudioPlaybackConfigs
                mPrevActiveAudioPlaybackConfigs = activeAudioPlaybackConfigs;
            }
        }
    }
}
