/*
 * Copyright 2018 The Android Open Source Project
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

import android.annotation.CallSuper;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.media.IMediaSession2;
import android.media.MediaController2;
import android.media.MediaSession2;
import android.media.SessionToken;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records a {@link MediaSession2} and holds {@link MediaController2}.
 * <p>
 * Owner of this object should handle synchronization.
 */
class MediaSession2Record {
    interface SessionDestroyedListener {
        void onSessionDestroyed(MediaSession2Record record);
    }

    private static final String TAG = "Session2Record";
    private static final boolean DEBUG = true; // TODO(jaewan): Change

    private final Context mContext;
    private final SessionDestroyedListener mSessionDestroyedListener;

    // TODO(jaewan): Replace these with the mContext.getMainExecutor()
    private final Handler mMainHandler;
    private final Executor mMainExecutor;

    private MediaController2 mController;
    private ControllerCallback mControllerCallback;

    private int mSessionPid;

    /**
     * Constructor
     */
    public MediaSession2Record(@NonNull Context context,
            @NonNull SessionDestroyedListener listener) {
        mContext = context;
        mSessionDestroyedListener = listener;

        mMainHandler = new Handler(Looper.getMainLooper());
        mMainExecutor = (runnable) -> {
            mMainHandler.post(runnable);
        };
    }

    public int getSessionPid() {
        return mSessionPid;
    }

    public Context getContext() {
        return mContext;
    }

    @CallSuper
    public void onSessionDestroyed() {
        if (mController != null) {
            mControllerCallback.destroy();
            mController.close();
            mController = null;
        }
        mSessionPid = 0;
    }

    /**
     * Create session token and tell server that session is now active.
     *
     * @param sessionPid session's pid
     * @return a token if successfully set, {@code null} if sanity check fails.
     */
    // TODO(jaewan): also add uid for multiuser support
    @CallSuper
    public @Nullable
    SessionToken createSessionToken(int sessionPid, String packageName, String id,
            IMediaSession2 sessionBinder) {
        if (mController != null) {
            if (mSessionPid != sessionPid) {
                // A package uses the same id for session across the different process.
                return null;
            }
            // If a session becomes inactive and then active again very quickly, previous 'inactive'
            // may not have delivered yet. Check if it's the case and destroy controller before
            // creating its session record to prevents getXXTokens() API from returning duplicated
            // tokens.
            // TODO(jaewan): Change this. If developer is really creating two sessions with the same
            //               id, this will silently invalidate previous session and no way for
            //               developers to know that.
            //               Instead, keep the list of static session ids from our APIs.
            //               Also change Controller2Impl.onConnectionChanged / getController.
            //               Also clean up ControllerCallback#destroy().
            if (DEBUG) {
                Log.d(TAG, "Session is recreated almost immediately. " + this);
            }
            onSessionDestroyed();
        }
        mController = onCreateMediaController(packageName, id, sessionBinder);
        mSessionPid = sessionPid;
        return mController.getSessionToken();
    }

    /**
     * Called when session becomes active and needs controller to listen session's activeness.
     * <p>
     * Should be overridden by subclasses to create token with its own extra information.
     */
    MediaController2 onCreateMediaController(
            String packageName, String id, IMediaSession2 sessionBinder) {
        SessionToken token = new SessionToken(
                SessionToken.TYPE_SESSION, packageName, id, null, sessionBinder);
        return createMediaController(token);
    }

    final MediaController2 createMediaController(SessionToken token) {
        mControllerCallback = new ControllerCallback();
        return new MediaController2(mContext, token, mControllerCallback, mMainExecutor);
    }

    /**
     * @return controller. Note that framework can only call oneway calls.
     */
    public SessionToken getToken() {
        return mController == null ? null : mController.getSessionToken();
    }

    @Override
    public String toString() {
        return getToken() == null
                ? "Token {null}"
                : "SessionRecord {pid=" + mSessionPid + ", " + getToken().toString() + "}";
    }

    private class ControllerCallback extends MediaController2.ControllerCallback {
        private final AtomicBoolean mIsActive = new AtomicBoolean(true);

        // This is called on the main thread with no lock. So place ensure followings.
        //   1. Don't touch anything in the parent class that needs synchronization.
        //      All other APIs in the MediaSession2Record assumes that server would use them with
        //      the lock hold.
        //   2. This can be called after the controller registered is released.
        @Override
        public void onDisconnected() {
            if (!mIsActive.get()) {
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "onDisconnected, token=" + getToken());
            }
            mSessionDestroyedListener.onSessionDestroyed(MediaSession2Record.this);
        }

        // TODO(jaewan): Remove this API when we revisit createSessionToken()
        public void destroy() {
            mIsActive.set(false);
        }
    };
}
