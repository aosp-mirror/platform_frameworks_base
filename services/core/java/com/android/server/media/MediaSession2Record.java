/*
 * Copyright 2019 The Android Open Source Project
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

import android.media.MediaController2;
import android.media.Session2CommandGroup;
import android.media.Session2Token;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.UserHandle;
import android.util.Log;
import android.view.KeyEvent;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;

/**
 * Keeps the record of {@link Session2Token} helps to send command to the corresponding session.
 */
// TODO(jaewan): Do not call service method directly -- introduce listener instead.
public class MediaSession2Record implements MediaSessionRecordImpl {
    private static final String TAG = "MediaSession2Record";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private final Session2Token mSessionToken;
    @GuardedBy("mLock")
    private final HandlerExecutor mHandlerExecutor;
    @GuardedBy("mLock")
    private final MediaController2 mController;
    @GuardedBy("mLock")
    private final MediaSessionService mService;
    @GuardedBy("mLock")
    private boolean mIsConnected;
    @GuardedBy("mLock")
    private int mPolicies;
    @GuardedBy("mLock")
    private boolean mIsClosed;

    public MediaSession2Record(Session2Token sessionToken, MediaSessionService service,
            Looper handlerLooper, int policies) {
        mSessionToken = sessionToken;
        mService = service;
        mHandlerExecutor = new HandlerExecutor(new Handler(handlerLooper));
        mController = new MediaController2.Builder(service.getContext(), sessionToken)
                .setControllerCallback(mHandlerExecutor, new Controller2Callback())
                .build();
        mPolicies = policies;
    }

    @Override
    public String getPackageName() {
        return mSessionToken.getPackageName();
    }

    public Session2Token getSession2Token() {
        return mSessionToken;
    }

    @Override
    public int getUid() {
        return mSessionToken.getUid();
    }

    @Override
    public int getUserId() {
        return UserHandle.getUserHandleForUid(mSessionToken.getUid()).getIdentifier();
    }

    @Override
    public boolean isSystemPriority() {
        // System priority session is currently only allowed for telephony, and it's OK to stick to
        // the media1 API at this moment.
        return false;
    }

    @Override
    public void adjustVolume(String packageName, String opPackageName, int pid, int uid,
            boolean asSystemService, int direction, int flags, boolean useSuggested) {
        // TODO(jaewan): Add API to adjust volume.
    }

    @Override
    public boolean isActive() {
        synchronized (mLock) {
            return mIsConnected;
        }
    }

    @Override
    public boolean checkPlaybackActiveState(boolean expected) {
        synchronized (mLock) {
            return mIsConnected && mController.isPlaybackActive() == expected;
        }
    }

    @Override
    public boolean isPlaybackTypeLocal() {
        // TODO(jaewan): Implement -- need API to know whether the playback is remote or local.
        return true;
    }

    @Override
    public void close() {
        synchronized (mLock) {
            mIsClosed = true;
            // Call close regardless of the mIsAvailable. This may be called when it's not yet
            // connected.
            mController.close();
        }
    }

    @Override
    public boolean isClosed() {
        synchronized (mLock) {
            return mIsClosed;
        }
    }

    @Override
    public boolean sendMediaButton(String packageName, int pid, int uid, boolean asSystemService,
            KeyEvent ke, int sequenceId, ResultReceiver cb) {
        // TODO(jaewan): Implement.
        return false;
    }

    @Override
    public int getSessionPolicies() {
        synchronized (mLock) {
            return mPolicies;
        }
    }

    @Override
    public void setSessionPolicies(int policies) {
        synchronized (mLock) {
            mPolicies = policies;
        }
    }

    @Override
    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "token=" + mSessionToken);
        pw.println(prefix + "controller=" + mController);

        final String indent = prefix + "  ";
        pw.println(indent + "playbackActive=" + mController.isPlaybackActive());
    }

    @Override
    public String toString() {
        // TODO(jaewan): Also add getId().
        return getPackageName() + " (userId=" + getUserId() + ")";
    }

    private class Controller2Callback extends MediaController2.ControllerCallback {
        @Override
        public void onConnected(MediaController2 controller, Session2CommandGroup allowedCommands) {
            if (DEBUG) {
                Log.d(TAG, "connected to " + mSessionToken + ", allowed=" + allowedCommands);
            }
            synchronized (mLock) {
                mIsConnected = true;
            }
            mService.onSessionActiveStateChanged(MediaSession2Record.this);
        }

        @Override
        public void onDisconnected(MediaController2 controller) {
            if (DEBUG) {
                Log.d(TAG, "disconnected from " + mSessionToken);
            }
            synchronized (mLock) {
                mIsConnected = false;
            }
            mService.onSessionDied(MediaSession2Record.this);
        }

        @Override
        public void onPlaybackActiveChanged(MediaController2 controller, boolean playbackActive) {
            if (DEBUG) {
                Log.d(TAG, "playback active changed, " + mSessionToken + ", active="
                        + playbackActive);
            }
            mService.onSessionPlaybackStateChanged(MediaSession2Record.this, playbackActive);
        }
    }
}
