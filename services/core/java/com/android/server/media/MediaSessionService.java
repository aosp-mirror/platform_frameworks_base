/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.media.Session2Token;
import android.os.IBinder;
import android.util.Log;

import com.android.server.SystemService;
import com.android.server.Watchdog;
import com.android.server.Watchdog.Monitor;

import java.util.List;

/**
 * System implementation of MediaSessionManager
 */
public class MediaSessionService extends SystemService implements Monitor {
    private static final String TAG = "MediaSessionService";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final ServiceImpl mImpl;

    public MediaSessionService(Context context) {
        super(context);
        mImpl = new MediaSessionServiceImpl(context);
    }

    @Override
    public void onStart() {
        publishBinderService(Context.MEDIA_SESSION_SERVICE, mImpl.getServiceBinder());
        Watchdog.getInstance().addMonitor(this);

        mImpl.onStart();
    }

    @Override
    public void onStartUser(int userId) {
        mImpl.onStartUser(userId);
    }

    @Override
    public void onSwitchUser(int userId) {
        mImpl.onSwitchUser(userId);
    }

    // Called when the user with the userId is removed.
    @Override
    public void onStopUser(int userId) {
        mImpl.onStopUser(userId);
    }

    @Override
    public void monitor() {
        mImpl.monitor();
    }

    /**
     * Updates session.
     */
    public void updateSession(MediaSessionRecord record) {
        mImpl.updateSession(record);
    }

    /**
     * Sets global priority session.
     */
    public void setGlobalPrioritySession(MediaSessionRecord record) {
        mImpl.setGlobalPrioritySession(record);
    }

    List<Session2Token> getSession2TokensLocked(int userId) {
        return mImpl.getSession2TokensLocked(userId);
    }

    /**
     * Tells the system UI that volume has changed on an active remote session.
     */
    public void notifyRemoteVolumeChanged(int flags, MediaSessionRecord session) {
        mImpl.notifyRemoteVolumeChanged(flags, session);
    }

    /**
     * Called when session playstate is changed.
     */
    public void onSessionPlaystateChanged(MediaSessionRecord record, int oldState, int newState) {
        mImpl.onSessionPlaystateChanged(record, oldState, newState);
    }

    /**
     * Called when session playback type is changed.
     */
    public void onSessionPlaybackTypeChanged(MediaSessionRecord record) {
        mImpl.onSessionPlaybackTypeChanged(record);
    }

    protected void enforcePhoneStatePermission(int pid, int uid) {
        mImpl.enforcePhoneStatePermission(pid, uid);
    }

    void sessionDied(MediaSessionRecord session) {
        mImpl.sessionDied(session);
    }

    void destroySession(MediaSessionRecord session) {
        mImpl.destroySession(session);
    }

    void pushSession2TokensChangedLocked(int userId) {
        mImpl.pushSession2TokensChangedLocked(userId);
    }

    /**
     * Called when media button receiver changed.
     */
    public void onMediaButtonReceiverChanged(MediaSessionRecord record) {
        mImpl.onMediaButtonReceiverChanged(record);
    }

    abstract static class ServiceImpl {
        public abstract void onStart();
        public abstract void notifyRemoteVolumeChanged(int flags, MediaSessionRecord session);
        public abstract void onSessionPlaystateChanged(
                MediaSessionRecord record, int oldState, int newState);
        public abstract void onSessionPlaybackTypeChanged(MediaSessionRecord record);
        public abstract void onStartUser(int userId);
        public abstract void onSwitchUser(int userId);
        public abstract void monitor();
        public abstract void onMediaButtonReceiverChanged(MediaSessionRecord record);
        protected abstract void enforcePhoneStatePermission(int pid, int uid);
        abstract void updateSession(MediaSessionRecord record);
        abstract void setGlobalPrioritySession(MediaSessionRecord record);
        abstract List<Session2Token> getSession2TokensLocked(int userId);
        abstract void onStopUser(int userId);
        abstract void sessionDied(MediaSessionRecord session);
        abstract void destroySession(MediaSessionRecord session);
        abstract void pushSession2TokensChangedLocked(int userId);
        abstract Context getContext();
        abstract IBinder getServiceBinder();
    }
}
