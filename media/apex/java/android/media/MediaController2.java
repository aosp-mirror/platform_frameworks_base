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
import static android.media.MediaConstants.KEY_PLAYBACK_ACTIVE;
import static android.media.MediaConstants.KEY_SESSION2LINK;
import static android.media.MediaConstants.KEY_TOKEN_EXTRAS;
import static android.media.Session2Command.RESULT_ERROR_UNKNOWN_ERROR;
import static android.media.Session2Command.RESULT_INFO_SKIPPED;
import static android.media.Session2Token.TYPE_SESSION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.util.ArrayMap;
import android.util.ArraySet;
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
    private final Handler mResultHandler;
    private final SessionServiceConnection mServiceConnection;

    private final Object mLock = new Object();
    //@GuardedBy("mLock")
    private boolean mClosed;
    //@GuardedBy("mLock")
    private int mNextSeqNumber;
    //@GuardedBy("mLock")
    private Session2Link mSessionBinder;
    //@GuardedBy("mLock")
    private Session2CommandGroup mAllowedCommands;
    //@GuardedBy("mLock")
    private Session2Token mConnectedToken;
    //@GuardedBy("mLock")
    private ArrayMap<ResultReceiver, Integer> mPendingCommands;
    //@GuardedBy("mLock")
    private ArraySet<Integer> mRequestedCommandSeqNumbers;
    //@GuardedBy("mLock")
    private boolean mPlaybackActive;

    /**
     * Create a {@link MediaController2} from the {@link Session2Token}.
     * This connects to the session and may wake up the service if it's not available.
     *
     * @param context Context
     * @param token token to connect to
     */
    public MediaController2(@NonNull Context context, @NonNull Session2Token token) {
        this(context, token, context.getMainExecutor(), new ControllerCallback() {});
    }

    /**
     * Create a {@link MediaController2} from the {@link Session2Token}.
     * This connects to the session and may wake up the service if it's not available.
     *
     * @param context Context
     * @param token token to connect to
     * @param executor executor to run callbacks on.
     * @param callback controller callback to receive changes in.
     */
    public MediaController2(@NonNull Context context, @NonNull Session2Token token,
            @NonNull Executor executor, @NonNull ControllerCallback callback) {
        if (context == null) {
            throw new IllegalArgumentException("context shouldn't be null");
        }
        if (token == null) {
            throw new IllegalArgumentException("token shouldn't be null");
        }
        mContext = context;
        mSessionToken = token;
        mCallbackExecutor = (executor == null) ? context.getMainExecutor() : executor;
        mCallback = (callback == null) ? new ControllerCallback() {} : callback;
        mControllerStub = new Controller2Link(this);
        // NOTE: mResultHandler uses main looper, so this MUST NOT be blocked.
        mResultHandler = new Handler(context.getMainLooper());

        mNextSeqNumber = 0;
        mPendingCommands = new ArrayMap<>();
        mRequestedCommandSeqNumbers = new ArraySet<>();

        boolean connectRequested;
        if (token.getType() == TYPE_SESSION) {
            mServiceConnection = null;
            connectRequested = requestConnectToSession();
        } else {
            mServiceConnection = new SessionServiceConnection();
            connectRequested = requestConnectToService();
        }
        if (!connectRequested) {
            close();
        }
    }

    @Override
    public void close() {
        synchronized (mLock) {
            if (mClosed) {
                // Already closed. Ignore rest of clean up code.
                // Note: unbindService() throws IllegalArgumentException when it's called twice.
                return;
            }
            mClosed = true;
            if (mServiceConnection != null) {
                // Note: This should be called even when the bindService() has returned false.
                mContext.unbindService(mServiceConnection);
            }
            if (mSessionBinder != null) {
                try {
                    mSessionBinder.disconnect(mControllerStub, getNextSeqNumber());
                    mSessionBinder.unlinkToDeath(mDeathRecipient, 0);
                } catch (RuntimeException e) {
                    // No-op
                }
            }
            mConnectedToken = null;
            mPendingCommands.clear();
            mRequestedCommandSeqNumbers.clear();
            mCallbackExecutor.execute(() -> {
                mCallback.onDisconnected(MediaController2.this);
            });
            mSessionBinder = null;
        }
    }

    /**
     * Returns {@link Session2Token} of the connected session.
     * If it is not connected yet, it returns {@code null}.
     * <p>
     * This may differ with the {@link Session2Token} from the constructor. For example, if the
     * controller is created with the token for {@link MediaSession2Service}, this would return
     * token for the {@link MediaSession2} in the service.
     *
     * @return Session2Token of the connected session, or {@code null} if not connected
     */
    @Nullable
    public Session2Token getConnectedSessionToken() {
        synchronized (mLock) {
            return mConnectedToken;
        }
    }

    /**
     * Returns whether the session's playback is active.
     *
     * @return {@code true} if playback active. {@code false} otherwise.
     * @see ControllerCallback#onPlaybackActiveChanged(MediaController2, boolean)
     */
    public boolean isPlaybackActive() {
        synchronized (mLock) {
            return mPlaybackActive;
        }
    }

    /**
     * Sends a session command to the session
     * <p>
     * @param command the session command
     * @param args optional arguments
     * @return a token which will be sent together in {@link ControllerCallback#onCommandResult}
     *        when its result is received.
     */
    @NonNull
    public Object sendSessionCommand(@NonNull Session2Command command, @Nullable Bundle args) {
        if (command == null) {
            throw new IllegalArgumentException("command shouldn't be null");
        }

        ResultReceiver resultReceiver = new ResultReceiver(mResultHandler) {
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                synchronized (mLock) {
                    mPendingCommands.remove(this);
                }
                mCallbackExecutor.execute(() -> {
                    mCallback.onCommandResult(MediaController2.this, this,
                            command, new Session2Command.Result(resultCode, resultData));
                });
            }
        };

        synchronized (mLock) {
            if (mSessionBinder != null) {
                int seq = getNextSeqNumber();
                mPendingCommands.put(resultReceiver, seq);
                try {
                    mSessionBinder.sendSessionCommand(mControllerStub, seq, command, args,
                            resultReceiver);
                } catch (RuntimeException e)  {
                    mPendingCommands.remove(resultReceiver);
                    resultReceiver.send(RESULT_ERROR_UNKNOWN_ERROR, null);
                }
            }
        }
        return resultReceiver;
    }

    /**
     * Cancels the session command previously sent.
     *
     * @param token the token which is returned from {@link #sendSessionCommand}.
     */
    public void cancelSessionCommand(@NonNull Object token) {
        if (token == null) {
            throw new IllegalArgumentException("token shouldn't be null");
        }
        synchronized (mLock) {
            if (mSessionBinder == null) return;
            Integer seq = mPendingCommands.remove(token);
            if (seq != null) {
                mSessionBinder.cancelSessionCommand(mControllerStub, seq);
            }
        }
    }

    // Called by Controller2Link.onConnected
    void onConnected(int seq, Bundle connectionResult) {
        Session2Link sessionBinder = connectionResult.getParcelable(KEY_SESSION2LINK);
        Session2CommandGroup allowedCommands =
                connectionResult.getParcelable(KEY_ALLOWED_COMMANDS);
        boolean playbackActive = connectionResult.getBoolean(KEY_PLAYBACK_ACTIVE);
        Bundle tokenExtras = connectionResult.getBundle(KEY_TOKEN_EXTRAS);
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
            mPlaybackActive = playbackActive;

            // Implementation for the local binder is no-op,
            // so can be used without worrying about deadlock.
            sessionBinder.linkToDeath(mDeathRecipient, 0);
            mConnectedToken = new Session2Token(mSessionToken.getUid(), TYPE_SESSION,
                    mSessionToken.getPackageName(), sessionBinder, tokenExtras);
        }
        mCallbackExecutor.execute(() -> {
            mCallback.onConnected(MediaController2.this, allowedCommands);
        });
    }

    // Called by Controller2Link.onDisconnected
    void onDisconnected(int seq) {
        // close() will call mCallback.onDisconnected
        close();
    }

    // Called by Controller2Link.onPlaybackActiveChanged
    void onPlaybackActiveChanged(int seq, boolean playbackActive) {
        synchronized (mLock) {
            mPlaybackActive = playbackActive;
        }
        mCallbackExecutor.execute(() -> {
            mCallback.onPlaybackActiveChanged(MediaController2.this, playbackActive);
        });
    }

    // Called by Controller2Link.onSessionCommand
    void onSessionCommand(int seq, Session2Command command, Bundle args,
            @Nullable ResultReceiver resultReceiver) {
        synchronized (mLock) {
            mRequestedCommandSeqNumbers.add(seq);
        }
        mCallbackExecutor.execute(() -> {
            boolean isCanceled;
            synchronized (mLock) {
                isCanceled = !mRequestedCommandSeqNumbers.remove(seq);
            }
            if (isCanceled) {
                if (resultReceiver != null) {
                    resultReceiver.send(RESULT_INFO_SKIPPED, null);
                }
                return;
            }
            Session2Command.Result result = mCallback.onSessionCommand(
                    MediaController2.this, command, args);
            if (resultReceiver != null) {
                if (result == null) {
                    resultReceiver.send(Session2Command.RESULT_INFO_SKIPPED, null);
                } else {
                    resultReceiver.send(result.getResultCode(), result.getResultData());
                }
            }
        });
    }

    // Called by Controller2Link.onSessionCommand
    void onCancelCommand(int seq) {
        synchronized (mLock) {
            mRequestedCommandSeqNumbers.remove(seq);
        }
    }

    private int getNextSeqNumber() {
        synchronized (mLock) {
            return mNextSeqNumber++;
        }
    }

    private Bundle createConnectionRequest() {
        Bundle connectionRequest = new Bundle();
        connectionRequest.putString(KEY_PACKAGE_NAME, mContext.getPackageName());
        connectionRequest.putInt(KEY_PID, Process.myPid());
        return connectionRequest;
    }

    private boolean requestConnectToSession() {
        Session2Link sessionBinder = mSessionToken.getSessionLink();
        Bundle connectionRequest = createConnectionRequest();
        try {
            sessionBinder.connect(mControllerStub, getNextSeqNumber(), connectionRequest);
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to call connection request", e);
            return false;
        }
        return true;
    }

    private boolean requestConnectToService() {
        // Service. Needs to get fresh binder whenever connection is needed.
        final Intent intent = new Intent(MediaSession2Service.SERVICE_INTERFACE);
        intent.setClassName(mSessionToken.getPackageName(), mSessionToken.getServiceName());

        // Use bindService() instead of startForegroundService() to start session service for three
        // reasons.
        // 1. Prevent session service owner's stopSelf() from destroying service.
        //    With the startForegroundService(), service's call of stopSelf() will trigger immediate
        //    onDestroy() calls on the main thread even when onConnect() is running in another
        //    thread.
        // 2. Minimize APIs for developers to take care about.
        //    With bindService(), developers only need to take care about Service.onBind()
        //    but Service.onStartCommand() should be also taken care about with the
        //    startForegroundService().
        // 3. Future support for UI-less playback
        //    If a service wants to keep running, it should be either foreground service or
        //    bound service. But there had been request for the feature for system apps
        //    and using bindService() will be better fit with it.
        synchronized (mLock) {
            boolean result = mContext.bindService(
                    intent, mServiceConnection, Context.BIND_AUTO_CREATE);
            if (!result) {
                Log.w(TAG, "bind to " + mSessionToken + " failed");
                return false;
            } else if (DEBUG) {
                Log.d(TAG, "bind to " + mSessionToken + " succeeded");
            }
        }
        return true;
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
                @NonNull Session2CommandGroup allowedCommands) {}

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
        public void onDisconnected(@NonNull MediaController2 controller) {}

        /**
         * Called when the session's playback activeness is changed.
         *
         * @param controller the controller for this event
         * @param playbackActive {@code true} if the session's playback is active.
         *                       {@code false} otherwise.
         * @see MediaController2#isPlaybackActive()
         */
        public void onPlaybackActiveChanged(@NonNull MediaController2 controller,
                boolean playbackActive) {}

        /**
         * Called when the connected session sent a session command.
         *
         * @param controller the controller for this event
         * @param command the session command
         * @param args optional arguments
         * @return the result for the session command. If {@code null}, RESULT_INFO_SKIPPED
         *         will be sent to the session.
         */
        @Nullable
        public Session2Command.Result onSessionCommand(@NonNull MediaController2 controller,
                @NonNull Session2Command command, @Nullable Bundle args) {
            return null;
        }

        /**
         * Called when the command sent to the connected session is finished.
         *
         * @param controller the controller for this event
         * @param token the token got from {@link MediaController2#sendSessionCommand}
         * @param command the session command
         * @param result the result of the session command
         */
        public void onCommandResult(@NonNull MediaController2 controller, @NonNull Object token,
                @NonNull Session2Command command, @NonNull Session2Command.Result result) {}
    }

    // This will be called on the main thread.
    private class SessionServiceConnection implements ServiceConnection {
        SessionServiceConnection() {
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            // Note that it's always main-thread.
            boolean connectRequested = false;
            try {
                if (DEBUG) {
                    Log.d(TAG, "onServiceConnected " + name + " " + this);
                }
                // Sanity check
                if (!mSessionToken.getPackageName().equals(name.getPackageName())) {
                    Log.wtf(TAG, "Expected connection to " + mSessionToken.getPackageName()
                            + " but is connected to " + name);
                    return;
                }
                IMediaSession2Service iService = IMediaSession2Service.Stub.asInterface(service);
                if (iService == null) {
                    Log.wtf(TAG, "Service interface is missing.");
                    return;
                }
                Bundle connectionRequest = createConnectionRequest();
                iService.connect(mControllerStub, getNextSeqNumber(), connectionRequest);
                connectRequested = true;
            } catch (RemoteException e) {
                Log.w(TAG, "Service " + name + " has died prematurely", e);
            } finally {
                if (!connectRequested) {
                    close();
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // Temporal lose of the binding because of the service crash. System will automatically
            // rebind, so just no-op.
            if (DEBUG) {
                Log.w(TAG, "Session service " + name + " is disconnected.");
            }
            close();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            // Permanent lose of the binding because of the service package update or removed.
            // This SessionServiceRecord will be removed accordingly, but forget session binder here
            // for sure.
            close();
        }
    }
}
