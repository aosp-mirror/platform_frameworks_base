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

import android.annotation.NonNull;
import android.content.Context;
import android.media.MediaController2;
import android.media.MediaSession2;
import android.media.SessionToken2;
import android.util.Log;
import java.util.concurrent.Executor;

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
    private final SessionToken2 mSessionToken;
    private final SessionDestroyedListener mSessionDestroyedListener;

    // TODO(jaewan): Replace these with the mContext.getMainExecutor()
    private final Executor mMainExecutor;

    private MediaController2 mController;

    /**
     * Constructor
     */
    public MediaSession2Record(@NonNull Context context, @NonNull SessionToken2 token,
            @NonNull SessionDestroyedListener listener) {
        mContext = context;
        mSessionToken = token;
        mSessionDestroyedListener = listener;
        mMainExecutor = (runnable) -> runnable.run();
    }

    public Context getContext() {
        return mContext;
    }

    public void onSessionDestroyed() {
        if (mController != null) {
            mController.close();
            // close() triggers ControllerCallback.onDisconnected() here already.
            mController = null;
        }
    }

    public boolean onSessionCreated(SessionToken2 token) {
        if (mController != null) {
            // Disclaimer: This may fail if following happens for an app.
            //             Step 1) Create a session in the process #1
            //             Step 2) Process #1 is killed
            //             Step 3) Before the death of process #1 is delivered,
            //                     (i.e. ControllerCallback#onDisconnected is called),
            //                     new process is started and create another session with the same
            //                     id in the new process.
            //             Step 4) fail!!! But this is tricky case that wouldn't happen in normal.
            Log.w(TAG, "Cannot create a new session with the id=" + token.getId() + " in the"
                    + " pkg=" + token.getPackageName() + ". ID should be unique in a package");
            return false;
        }
        mController = new MediaController2(mContext, token, mMainExecutor,
                new ControllerCallback());
        return true;
    }

    /**
     * @return token
     */
    public SessionToken2 getToken() {
        return mSessionToken;
    }

    /**
     * @return controller
     */
    public MediaController2 getController() {
        return mController;
    }

    @Override
    public String toString() {
        return getToken() == null
                ? "Token {null}" : "SessionRecord {" + getToken().toString() + "}";
    }

    private class ControllerCallback extends MediaController2.ControllerCallback {
        // This is called on the random thread with no lock. So place ensure followings.
        //   1. Don't touch anything in the parent class that needs synchronization.
        //      All other APIs in the MediaSession2Record assumes that server would use them with
        //      the lock hold.
        //   2. This can be called after the controller registered is closed.
        @Override
        public void onDisconnected() {
            if (DEBUG) {
                Log.d(TAG, "onDisconnected, token=" + getToken());
            }
            mSessionDestroyedListener.onSessionDestroyed(MediaSession2Record.this);
        }
    };
}
