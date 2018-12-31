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

package android.media;

import static android.media.MediaConstants.KEY_ALLOWED_COMMANDS;
import static android.media.MediaConstants.KEY_PACKAGE_NAME;
import static android.media.MediaConstants.KEY_PID;
import static android.media.MediaConstants.KEY_SESSION2_STUB;
import static android.media.Session2Token.TYPE_SESSION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import java.util.concurrent.Executor;

/**
 * Allows an app to interact with an active {@link MediaSession2} or a
 * {@link MediaSession2Service} which would provide {@link MediaSession2}. Media buttons and other
 * commands can be sent to the session.
 * <p>
 * This API is not generally intended for third party application developers.
 * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
 * <a href="{@docRoot}reference/androidx/media2/package-summary.html">Media2 Library</a>
 * for consistent behavior across all devices.
 * @hide
 */
public class MediaController2 implements AutoCloseable {
    static final String TAG = "MediaController2";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final ControllerCallback mCallback;

    private final IBinder.DeathRecipient mDeathRecipient = () -> close();
    private final Context mContext;
    private final Session2Token mSessionToken;
    private final Executor mCallbackExecutor;
    private final Controller2Link mControllerStub;

    private final Object mLock = new Object();
    //@GuardedBy("mLock")
    private int mNextSeqNumber;
    //@GuardedBy("mLock")
    private Session2Link mSessionBinder;
    //@GuardedBy("mLock")
    private Session2CommandGroup mAllowedCommands;
    //@GuardedBy("mLock")
    private Session2Token mConnectedToken;

    /**
     * Create a {@link MediaController2} from the {@link Session2Token}.
     * This connects to the session and may wake up the service if it's not available.
     *
     * @param context Context
     * @param token token to connect to
     * @param executor executor to run callbacks on.
     * @param callback controller callback to receive changes in
     */
    public MediaController2(@NonNull final Context context, @NonNull final Session2Token token,
            @NonNull final Executor executor, @NonNull final ControllerCallback callback) {
        if (context == null) {
            throw new IllegalArgumentException("context shouldn't be null");
        }
        if (token == null) {
            throw new IllegalArgumentException("token shouldn't be null");
        }
        if (callback == null) {
            throw new IllegalArgumentException("callback shouldn't be null");
        }
        if (executor == null) {
            throw new IllegalArgumentException("executor shouldn't be null");
        }
        mContext = context;
        mSessionToken = token;
        mCallbackExecutor = executor;
        mCallback = callback;
        mControllerStub = new Controller2Link(this);

        mNextSeqNumber = 0;

        if (token.getType() == TYPE_SESSION) {
            connectToSession();
        } else {
            // TODO: Handle connect to session service.
        }
    }

    @Override
    public void close() {
        synchronized (mLock) {
            if (mSessionBinder != null) {
                try {
                    mSessionBinder.unlinkToDeath(mDeathRecipient, 0);
                    mSessionBinder.disconnect(mControllerStub, mNextSeqNumber++);
                } catch (RuntimeException e)  {
                    // No-op
                }
            }
            mCallbackExecutor.execute(() -> {
                mCallback.onDisconnected(MediaController2.this);
            });
            mSessionBinder = null;
        }
    }

    // Called by Controller2Link.onConnected
    void onConnected(int seq, Bundle connectionResult) {
        final long token = Binder.clearCallingIdentity();
        try {
            Session2Link sessionBinder = connectionResult.getParcelable(KEY_SESSION2_STUB);
            Session2CommandGroup allowedCommands =
                    connectionResult.getParcelable(KEY_ALLOWED_COMMANDS);
            if (DEBUG) {
                Log.d(TAG, "notifyConnected sessionBinder=" + sessionBinder
                        + ", allowedCommands=" + allowedCommands);
            }
            if (sessionBinder == null || allowedCommands == null) {
                // Connection rejected.
                close();
                return;
            }
            synchronized (mLock) {
                mSessionBinder = sessionBinder;
                mAllowedCommands = allowedCommands;
                // Implementation for the local binder is no-op,
                // so can be used without worrying about deadlock.
                sessionBinder.linkToDeath(mDeathRecipient, 0);
                mConnectedToken = new Session2Token(mSessionToken.getUid(), TYPE_SESSION,
                        mSessionToken.getPackageName(), sessionBinder);
            }
            mCallbackExecutor.execute(() -> {
                mCallback.onConnected(MediaController2.this, allowedCommands);
            });
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // Called by Controller2Link.onDisconnected
    void onDisconnected(int seq) {
        final long token = Binder.clearCallingIdentity();
        try {
            // close() will call mCallback.onDisconnected
            close();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // Called by Controller2Link.onSessionCommand
    void onSessionCommand(int seq, Session2Command command, Bundle args) {
        // TODO: Implement this
    }

    private int getNextSeqNumber() {
        synchronized (mLock) {
            return mNextSeqNumber++;
        }
    }

    private void connectToSession() {
        Session2Link sessionBinder = mSessionToken.getSessionLink();
        Bundle connectionRequest = new Bundle();
        connectionRequest.putString(KEY_PACKAGE_NAME, mContext.getPackageName());
        connectionRequest.putInt(KEY_PID, Process.myPid());

        try {
            sessionBinder.connect(mControllerStub, getNextSeqNumber(), connectionRequest);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to call connection request. Framework will retry"
                    + " automatically");
        }
    }

    /**
     * Interface for listening to change in activeness of the {@link MediaSession2}.
     * <p>
     * This API is not generally intended for third party application developers.
     */
    public abstract static class ControllerCallback {
        /**
         * Called when the controller is successfully connected to the session. The controller
         * becomes available afterwards.
         *
         * @param controller the controller for this event
         * @param allowedCommands commands that's allowed by the session.
         */
        public void onConnected(@NonNull MediaController2 controller,
                @NonNull Session2CommandGroup allowedCommands) { }

        /**
         * Called when the session refuses the controller or the controller is disconnected from
         * the session. The controller becomes unavailable afterwards and the callback wouldn't
         * be called.
         * <p>
         * It will be also called after the {@link #close()}, so you can put clean up code here.
         * You don't need to call {@link #close()} after this.
         *
         * @param controller the controller for this event
         */
        public void onDisconnected(@NonNull MediaController2 controller) { }

        /**
         * Called when a controller sent a session command.
         */
        public void onSessionCommand(@NonNull MediaController2 controller,
                @NonNull Session2Command command, @Nullable Bundle args) {
        }
    }
}
