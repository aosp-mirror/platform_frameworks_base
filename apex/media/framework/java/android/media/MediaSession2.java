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
import static android.media.MediaConstants.KEY_CONNECTION_HINTS;
import static android.media.MediaConstants.KEY_PACKAGE_NAME;
import static android.media.MediaConstants.KEY_PID;
import static android.media.MediaConstants.KEY_PLAYBACK_ACTIVE;
import static android.media.MediaConstants.KEY_SESSION2LINK;
import static android.media.MediaConstants.KEY_TOKEN_EXTRAS;
import static android.media.Session2Command.Result.RESULT_ERROR_UNKNOWN_ERROR;
import static android.media.Session2Command.Result.RESULT_INFO_SKIPPED;
import static android.media.Session2Token.TYPE_SESSION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.session.MediaSessionManager;
import android.media.session.MediaSessionManager.RemoteUserInfo;
import android.os.BadParcelableException;
import android.os.Bundle;
import android.os.Handler;
import android.os.Parcel;
import android.os.Process;
import android.os.ResultReceiver;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.modules.utils.build.SdkLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * This API is not generally intended for third party application developers.
 * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
 * <a href="{@docRoot}reference/androidx/media2/session/package-summary.html">Media2 session
 * Library</a> for consistent behavior across all devices.
 * <p>
 * Allows a media app to expose its transport controls and playback information in a process to
 * other processes including the Android framework and other apps.
 */
public class MediaSession2 implements AutoCloseable {
    static final String TAG = "MediaSession2";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // Note: This checks the uniqueness of a session ID only in a single process.
    // When the framework becomes able to check the uniqueness, this logic should be removed.
    //@GuardedBy("MediaSession.class")
    private static final List<String> SESSION_ID_LIST = new ArrayList<>();

    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final Object mLock = new Object();
    //@GuardedBy("mLock")
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final Map<Controller2Link, ControllerInfo> mConnectedControllers = new HashMap<>();

    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final Context mContext;
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final Executor mCallbackExecutor;
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final SessionCallback mCallback;
    @SuppressWarnings("WeakerAccess") /* synthetic access */
    final Session2Link mSessionStub;

    private final String mSessionId;
    private final PendingIntent mSessionActivity;
    private final Session2Token mSessionToken;
    private final MediaSessionManager mMediaSessionManager;
    private final MediaCommunicationManager mCommunicationManager;
    private final Handler mResultHandler;

    //@GuardedBy("mLock")
    private boolean mClosed;
    //@GuardedBy("mLock")
    private boolean mPlaybackActive;
    //@GuardedBy("mLock")
    private ForegroundServiceEventCallback mForegroundServiceEventCallback;

    MediaSession2(@NonNull Context context, @NonNull String id, PendingIntent sessionActivity,
            @NonNull Executor callbackExecutor, @NonNull SessionCallback callback,
            @NonNull Bundle tokenExtras) {
        synchronized (MediaSession2.class) {
            if (SESSION_ID_LIST.contains(id)) {
                throw new IllegalStateException("Session ID must be unique. ID=" + id);
            }
            SESSION_ID_LIST.add(id);
        }

        mContext = context;
        mSessionId = id;
        mSessionActivity = sessionActivity;
        mCallbackExecutor = callbackExecutor;
        mCallback = callback;
        mSessionStub = new Session2Link(this);
        mSessionToken = new Session2Token(Process.myUid(), TYPE_SESSION, context.getPackageName(),
                mSessionStub, tokenExtras);
        if (SdkLevel.isAtLeastS()) {
            mCommunicationManager = mContext.getSystemService(MediaCommunicationManager.class);
            mMediaSessionManager = null;
        } else {
            mMediaSessionManager = mContext.getSystemService(MediaSessionManager.class);
            mCommunicationManager = null;
        }
        // NOTE: mResultHandler uses main looper, so this MUST NOT be blocked.
        mResultHandler = new Handler(context.getMainLooper());
        mClosed = false;
    }

    @Override
    public void close() {
        try {
            List<ControllerInfo> controllerInfos;
            ForegroundServiceEventCallback callback;
            synchronized (mLock) {
                if (mClosed) {
                    return;
                }
                mClosed = true;
                controllerInfos = getConnectedControllers();
                mConnectedControllers.clear();
                callback = mForegroundServiceEventCallback;
                mForegroundServiceEventCallback = null;
            }
            synchronized (MediaSession2.class) {
                SESSION_ID_LIST.remove(mSessionId);
            }
            if (callback != null) {
                callback.onSessionClosed(this);
            }
            for (ControllerInfo info : controllerInfos) {
                info.notifyDisconnected();
            }
        } catch (Exception e) {
            // Should not be here.
        }
    }

    /**
     * Returns the session ID
     */
    @NonNull
    public String getId() {
        return mSessionId;
    }

    /**
     * Returns the {@link Session2Token} for creating {@link MediaController2}.
     */
    @NonNull
    public Session2Token getToken() {
        return mSessionToken;
    }

    /**
     * Broadcasts a session command to all the connected controllers
     * <p>
     * @param command the session command
     * @param args optional arguments
     */
    public void broadcastSessionCommand(@NonNull Session2Command command, @Nullable Bundle args) {
        if (command == null) {
            throw new IllegalArgumentException("command shouldn't be null");
        }
        List<ControllerInfo> controllerInfos = getConnectedControllers();
        for (ControllerInfo controller : controllerInfos) {
            controller.sendSessionCommand(command, args, null);
        }
    }

    /**
     * Sends a session command to a specific controller
     * <p>
     * @param controller the controller to get the session command
     * @param command the session command
     * @param args optional arguments
     * @return a token which will be sent together in {@link SessionCallback#onCommandResult}
     *     when its result is received.
     */
    @NonNull
    public Object sendSessionCommand(@NonNull ControllerInfo controller,
            @NonNull Session2Command command, @Nullable Bundle args) {
        if (controller == null) {
            throw new IllegalArgumentException("controller shouldn't be null");
        }
        if (command == null) {
            throw new IllegalArgumentException("command shouldn't be null");
        }
        ResultReceiver resultReceiver = new ResultReceiver(mResultHandler) {
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                controller.receiveCommandResult(this);
                mCallbackExecutor.execute(() -> {
                    mCallback.onCommandResult(MediaSession2.this, controller, this,
                            command, new Session2Command.Result(resultCode, resultData));
                });
            }
        };
        controller.sendSessionCommand(command, args, resultReceiver);
        return resultReceiver;
    }

    /**
     * Cancels the session command previously sent.
     *
     * @param controller the controller to get the session command
     * @param token the token which is returned from {@link #sendSessionCommand}.
     */
    public void cancelSessionCommand(@NonNull ControllerInfo controller, @NonNull Object token) {
        if (controller == null) {
            throw new IllegalArgumentException("controller shouldn't be null");
        }
        if (token == null) {
            throw new IllegalArgumentException("token shouldn't be null");
        }
        controller.cancelSessionCommand(token);
    }

    /**
     * Sets whether the playback is active (i.e. playing something)
     *
     * @param playbackActive {@code true} if the playback active, {@code false} otherwise.
     **/
    public void setPlaybackActive(boolean playbackActive) {
        final ForegroundServiceEventCallback serviceCallback;
        synchronized (mLock) {
            if (mPlaybackActive == playbackActive) {
                return;
            }
            mPlaybackActive = playbackActive;
            serviceCallback = mForegroundServiceEventCallback;
        }
        if (serviceCallback != null) {
            serviceCallback.onPlaybackActiveChanged(this, playbackActive);
        }
        List<ControllerInfo> controllerInfos = getConnectedControllers();
        for (ControllerInfo controller : controllerInfos) {
            controller.notifyPlaybackActiveChanged(playbackActive);
        }
    }

    /**
     * Returns whether the playback is active (i.e. playing something)
     *
     * @return {@code true} if the playback active, {@code false} otherwise.
     */
    public boolean isPlaybackActive() {
        synchronized (mLock) {
            return mPlaybackActive;
        }
    }

    /**
     * Gets the list of the connected controllers
     *
     * @return list of the connected controllers.
     */
    @NonNull
    public List<ControllerInfo> getConnectedControllers() {
        List<ControllerInfo> controllers = new ArrayList<>();
        synchronized (mLock) {
            controllers.addAll(mConnectedControllers.values());
        }
        return controllers;
    }

    /**
     * Returns whether the given bundle includes non-framework Parcelables.
     */
    static boolean hasCustomParcelable(@Nullable Bundle bundle) {
        if (bundle == null) {
            return false;
        }

        // Try writing the bundle to parcel, and read it with framework classloader.
        Parcel parcel = null;
        try {
            parcel = Parcel.obtain();
            parcel.writeBundle(bundle);
            parcel.setDataPosition(0);
            Bundle out = parcel.readBundle(null);

            // Calling Bundle#size() will trigger Bundle#unparcel().
            out.size();
        } catch (BadParcelableException e) {
            Log.d(TAG, "Custom parcelable in bundle.", e);
            return true;
        } finally {
            if (parcel != null) {
                parcel.recycle();
            }
        }
        return false;
    }

    boolean isClosed() {
        synchronized (mLock) {
            return mClosed;
        }
    }

    SessionCallback getCallback() {
        return mCallback;
    }

    boolean isTrustedForMediaControl(RemoteUserInfo remoteUserInfo) {
        if (SdkLevel.isAtLeastS()) {
            return mCommunicationManager.isTrustedForMediaControl(remoteUserInfo);
        } else {
            return mMediaSessionManager.isTrustedForMediaControl(remoteUserInfo);
        }
    }

    void setForegroundServiceEventCallback(ForegroundServiceEventCallback callback) {
        synchronized (mLock) {
            if (mForegroundServiceEventCallback == callback) {
                return;
            }
            if (mForegroundServiceEventCallback != null && callback != null) {
                throw new IllegalStateException("A session cannot be added to multiple services");
            }
            mForegroundServiceEventCallback = callback;
        }
    }

    // Called by Session2Link.onConnect and MediaSession2Service.MediaSession2ServiceStub.connect
    void onConnect(final Controller2Link controller, int callingPid, int callingUid, int seq,
            Bundle connectionRequest) {
        if (callingPid == 0) {
            // The pid here is from Binder.getCallingPid(), which can be 0 for an oneway call from
            // the remote process. If it's the case, use PID from the connectionRequest.
            callingPid = connectionRequest.getInt(KEY_PID);
        }
        String callingPkg = connectionRequest.getString(KEY_PACKAGE_NAME);

        RemoteUserInfo remoteUserInfo = new RemoteUserInfo(callingPkg, callingPid, callingUid);

        Bundle connectionHints = connectionRequest.getBundle(KEY_CONNECTION_HINTS);
        if (connectionHints == null) {
            Log.w(TAG, "connectionHints shouldn't be null.");
            connectionHints = Bundle.EMPTY;
        } else if (hasCustomParcelable(connectionHints)) {
            Log.w(TAG, "connectionHints contain custom parcelable. Ignoring.");
            connectionHints = Bundle.EMPTY;
        }

        final ControllerInfo controllerInfo = new ControllerInfo(
                remoteUserInfo,
                isTrustedForMediaControl(remoteUserInfo),
                controller,
                connectionHints);
        mCallbackExecutor.execute(() -> {
            boolean connected = false;
            try {
                if (isClosed()) {
                    return;
                }
                controllerInfo.mAllowedCommands =
                        mCallback.onConnect(MediaSession2.this, controllerInfo);
                // Don't reject connection for the request from trusted app.
                // Otherwise server will fail to retrieve session's information to dispatch
                // media keys to.
                if (controllerInfo.mAllowedCommands == null && !controllerInfo.isTrusted()) {
                    return;
                }
                if (controllerInfo.mAllowedCommands == null) {
                    // For trusted apps, send non-null allowed commands to keep
                    // connection.
                    controllerInfo.mAllowedCommands =
                            new Session2CommandGroup.Builder().build();
                }
                if (DEBUG) {
                    Log.d(TAG, "Accepting connection: " + controllerInfo);
                }
                // If connection is accepted, notify the current state to the controller.
                // It's needed because we cannot call synchronous calls between
                // session/controller.
                Bundle connectionResult = new Bundle();
                connectionResult.putParcelable(KEY_SESSION2LINK, mSessionStub);
                connectionResult.putParcelable(KEY_ALLOWED_COMMANDS,
                        controllerInfo.mAllowedCommands);
                connectionResult.putBoolean(KEY_PLAYBACK_ACTIVE, isPlaybackActive());
                connectionResult.putBundle(KEY_TOKEN_EXTRAS, mSessionToken.getExtras());

                // Double check if session is still there, because close() can be called in
                // another thread.
                if (isClosed()) {
                    return;
                }
                controllerInfo.notifyConnected(connectionResult);
                synchronized (mLock) {
                    if (mConnectedControllers.containsKey(controller)) {
                        Log.w(TAG, "Controller " + controllerInfo + " has sent connection"
                                + " request multiple times");
                    }
                    mConnectedControllers.put(controller, controllerInfo);
                }
                mCallback.onPostConnect(MediaSession2.this, controllerInfo);
                connected = true;
            } finally {
                if (!connected || isClosed()) {
                    if (DEBUG) {
                        Log.d(TAG, "Rejecting connection or notifying that session is closed"
                                + ", controllerInfo=" + controllerInfo);
                    }
                    synchronized (mLock) {
                        mConnectedControllers.remove(controller);
                    }
                    controllerInfo.notifyDisconnected();
                }
            }
        });
    }

    // Called by Session2Link.onDisconnect
    void onDisconnect(@NonNull final Controller2Link controller, int seq) {
        final ControllerInfo controllerInfo;
        synchronized (mLock) {
            controllerInfo = mConnectedControllers.remove(controller);
        }
        if (controllerInfo == null) {
            return;
        }
        mCallbackExecutor.execute(() -> {
            mCallback.onDisconnected(MediaSession2.this, controllerInfo);
        });
    }

    // Called by Session2Link.onSessionCommand
    void onSessionCommand(@NonNull final Controller2Link controller, final int seq,
            final Session2Command command, final Bundle args,
            @Nullable ResultReceiver resultReceiver) {
        if (controller == null) {
            return;
        }
        final ControllerInfo controllerInfo;
        synchronized (mLock) {
            controllerInfo = mConnectedControllers.get(controller);
        }
        if (controllerInfo == null) {
            return;
        }

        // TODO: check allowed commands.
        synchronized (mLock) {
            controllerInfo.addRequestedCommandSeqNumber(seq);
        }
        mCallbackExecutor.execute(() -> {
            if (!controllerInfo.removeRequestedCommandSeqNumber(seq)) {
                if (resultReceiver != null) {
                    resultReceiver.send(RESULT_INFO_SKIPPED, null);
                }
                return;
            }
            Session2Command.Result result = mCallback.onSessionCommand(
                    MediaSession2.this, controllerInfo, command, args);
            if (resultReceiver != null) {
                if (result == null) {
                    resultReceiver.send(RESULT_INFO_SKIPPED, null);
                } else {
                    resultReceiver.send(result.getResultCode(), result.getResultData());
                }
            }
        });
    }

    // Called by Session2Link.onCancelCommand
    void onCancelCommand(@NonNull final Controller2Link controller, final int seq) {
        final ControllerInfo controllerInfo;
        synchronized (mLock) {
            controllerInfo = mConnectedControllers.get(controller);
        }
        if (controllerInfo == null) {
            return;
        }
        controllerInfo.removeRequestedCommandSeqNumber(seq);
    }

    /**
     * This API is not generally intended for third party application developers.
     * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
     * <a href="{@docRoot}reference/androidx/media2/session/package-summary.html">Media2 session
     * Library</a> for consistent behavior across all devices.
     * <p>
     * Builder for {@link MediaSession2}.
     * <p>
     * Any incoming event from the {@link MediaController2} will be handled on the callback
     * executor. If it's not set, {@link Context#getMainExecutor()} will be used by default.
     */
    public static final class Builder {
        private Context mContext;
        private String mId;
        private PendingIntent mSessionActivity;
        private Executor mCallbackExecutor;
        private SessionCallback mCallback;
        private Bundle mExtras;

        /**
         * Creates a builder for {@link MediaSession2}.
         *
         * @param context Context
         * @throws IllegalArgumentException if context is {@code null}.
         */
        public Builder(@NonNull Context context) {
            if (context == null) {
                throw new IllegalArgumentException("context shouldn't be null");
            }
            mContext = context;
        }

        /**
         * Set an intent for launching UI for this Session. This can be used as a
         * quick link to an ongoing media screen. The intent should be for an
         * activity that may be started using {@link Context#startActivity(Intent)}.
         *
         * @param pi The intent to launch to show UI for this session.
         * @return The Builder to allow chaining
         */
        @NonNull
        public Builder setSessionActivity(@Nullable PendingIntent pi) {
            mSessionActivity = pi;
            return this;
        }

        /**
         * Set ID of the session. If it's not set, an empty string will be used to create a session.
         * <p>
         * Use this if and only if your app supports multiple playback at the same time and also
         * wants to provide external apps to have finer controls of them.
         *
         * @param id id of the session. Must be unique per package.
         * @throws IllegalArgumentException if id is {@code null}.
         * @return The Builder to allow chaining
         */
        @NonNull
        public Builder setId(@NonNull String id) {
            if (id == null) {
                throw new IllegalArgumentException("id shouldn't be null");
            }
            mId = id;
            return this;
        }

        /**
         * Set callback for the session and its executor.
         *
         * @param executor callback executor
         * @param callback session callback.
         * @return The Builder to allow chaining
         */
        @NonNull
        public Builder setSessionCallback(@NonNull Executor executor,
                @NonNull SessionCallback callback) {
            mCallbackExecutor = executor;
            mCallback = callback;
            return this;
        }

        /**
         * Set extras for the session token. If null or not set, {@link Session2Token#getExtras()}
         * will return an empty {@link Bundle}. An {@link IllegalArgumentException} will be thrown
         * if the bundle contains any non-framework Parcelable objects.
         *
         * @return The Builder to allow chaining
         * @see Session2Token#getExtras()
         */
        @NonNull
        public Builder setExtras(@NonNull Bundle extras) {
            if (extras == null) {
                throw new NullPointerException("extras shouldn't be null");
            }
            if (hasCustomParcelable(extras)) {
                throw new IllegalArgumentException(
                        "extras shouldn't contain any custom parcelables");
            }
            mExtras = new Bundle(extras);
            return this;
        }

        /**
         * Build {@link MediaSession2}.
         *
         * @return a new session
         * @throws IllegalStateException if the session with the same id is already exists for the
         *      package.
         */
        @NonNull
        public MediaSession2 build() {
            if (mCallbackExecutor == null) {
                mCallbackExecutor = mContext.getMainExecutor();
            }
            if (mCallback == null) {
                mCallback = new SessionCallback() {};
            }
            if (mId == null) {
                mId = "";
            }
            if (mExtras == null) {
                mExtras = Bundle.EMPTY;
            }
            MediaSession2 session2 = new MediaSession2(mContext, mId, mSessionActivity,
                    mCallbackExecutor, mCallback, mExtras);

            // Notify framework about the newly create session after the constructor is finished.
            // Otherwise, framework may access the session before the initialization is finished.
            try {
                if (SdkLevel.isAtLeastS()) {
                    MediaCommunicationManager manager =
                            mContext.getSystemService(MediaCommunicationManager.class);
                    manager.notifySession2Created(session2.getToken());
                } else {
                    MediaSessionManager manager =
                            mContext.getSystemService(MediaSessionManager.class);
                    manager.notifySession2Created(session2.getToken());
                }
            } catch (Exception e) {
                session2.close();
                throw e;
            }

            return session2;
        }
    }

    /**
     * This API is not generally intended for third party application developers.
     * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
     * <a href="{@docRoot}reference/androidx/media2/session/package-summary.html">Media2 session
     * Library</a> for consistent behavior across all devices.
     * <p>
     * Information of a controller.
     */
    public static final class ControllerInfo {
        private final RemoteUserInfo mRemoteUserInfo;
        private final boolean mIsTrusted;
        private final Controller2Link mControllerBinder;
        private final Bundle mConnectionHints;
        private final Object mLock = new Object();
        //@GuardedBy("mLock")
        private int mNextSeqNumber;
        //@GuardedBy("mLock")
        private ArrayMap<ResultReceiver, Integer> mPendingCommands;
        //@GuardedBy("mLock")
        private ArraySet<Integer> mRequestedCommandSeqNumbers;

        @SuppressWarnings("WeakerAccess") /* synthetic access */
        Session2CommandGroup mAllowedCommands;

        /**
         * @param remoteUserInfo remote user info
         * @param trusted {@code true} if trusted, {@code false} otherwise
         * @param controllerBinder Controller2Link for the connected controller.
         * @param connectionHints a session-specific argument sent from the controller for the
         *                        connection. The contents of this bundle may affect the
         *                        connection result.
         */
        ControllerInfo(@NonNull RemoteUserInfo remoteUserInfo, boolean trusted,
                @Nullable Controller2Link controllerBinder, @NonNull Bundle connectionHints) {
            mRemoteUserInfo = remoteUserInfo;
            mIsTrusted = trusted;
            mControllerBinder = controllerBinder;
            mConnectionHints = connectionHints;
            mPendingCommands = new ArrayMap<>();
            mRequestedCommandSeqNumbers = new ArraySet<>();
        }

        /**
         * @return remote user info of the controller.
         */
        @NonNull
        public RemoteUserInfo getRemoteUserInfo() {
            return mRemoteUserInfo;
        }

        /**
         * @return package name of the controller.
         */
        @NonNull
        public String getPackageName() {
            return mRemoteUserInfo.getPackageName();
        }

        /**
         * @return uid of the controller. Can be a negative value if the uid cannot be obtained.
         */
        public int getUid() {
            return mRemoteUserInfo.getUid();
        }

        /**
         * @return connection hints sent from controller.
         */
        @NonNull
        public Bundle getConnectionHints() {
            return new Bundle(mConnectionHints);
        }

        /**
         * Return if the controller has granted {@code android.permission.MEDIA_CONTENT_CONTROL} or
         * has a enabled notification listener so can be trusted to accept connection and incoming
         * command request.
         *
         * @return {@code true} if the controller is trusted.
         * @hide
         */
        public boolean isTrusted() {
            return mIsTrusted;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mControllerBinder, mRemoteUserInfo);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (!(obj instanceof ControllerInfo)) return false;
            if (this == obj) return true;

            ControllerInfo other = (ControllerInfo) obj;
            if (mControllerBinder != null || other.mControllerBinder != null) {
                return Objects.equals(mControllerBinder, other.mControllerBinder);
            }
            return mRemoteUserInfo.equals(other.mRemoteUserInfo);
        }

        @Override
        @NonNull
        public String toString() {
            return "ControllerInfo {pkg=" + mRemoteUserInfo.getPackageName() + ", uid="
                    + mRemoteUserInfo.getUid() + ", allowedCommands=" + mAllowedCommands + "})";
        }

        void notifyConnected(Bundle connectionResult) {
            if (mControllerBinder == null) return;

            try {
                mControllerBinder.notifyConnected(getNextSeqNumber(), connectionResult);
            } catch (RuntimeException e) {
                // Controller may be died prematurely.
            }
        }

        void notifyDisconnected() {
            if (mControllerBinder == null) return;

            try {
                mControllerBinder.notifyDisconnected(getNextSeqNumber());
            } catch (RuntimeException e) {
                // Controller may be died prematurely.
            }
        }

        void notifyPlaybackActiveChanged(boolean playbackActive) {
            if (mControllerBinder == null) return;

            try {
                mControllerBinder.notifyPlaybackActiveChanged(getNextSeqNumber(), playbackActive);
            } catch (RuntimeException e) {
                // Controller may be died prematurely.
            }
        }

        void sendSessionCommand(Session2Command command, Bundle args,
                ResultReceiver resultReceiver) {
            if (mControllerBinder == null) return;

            try {
                int seq = getNextSeqNumber();
                synchronized (mLock) {
                    mPendingCommands.put(resultReceiver, seq);
                }
                mControllerBinder.sendSessionCommand(seq, command, args, resultReceiver);
            } catch (RuntimeException e) {
                // Controller may be died prematurely.
                synchronized (mLock) {
                    mPendingCommands.remove(resultReceiver);
                }
                resultReceiver.send(RESULT_ERROR_UNKNOWN_ERROR, null);
            }
        }

        void cancelSessionCommand(@NonNull Object token) {
            if (mControllerBinder == null) return;
            Integer seq;
            synchronized (mLock) {
                seq = mPendingCommands.remove(token);
            }
            if (seq != null) {
                mControllerBinder.cancelSessionCommand(seq);
            }
        }

        void receiveCommandResult(ResultReceiver resultReceiver) {
            synchronized (mLock) {
                mPendingCommands.remove(resultReceiver);
            }
        }

        void addRequestedCommandSeqNumber(int seq) {
            synchronized (mLock) {
                mRequestedCommandSeqNumbers.add(seq);
            }
        }

        boolean removeRequestedCommandSeqNumber(int seq) {
            synchronized (mLock) {
                return mRequestedCommandSeqNumbers.remove(seq);
            }
        }

        private int getNextSeqNumber() {
            synchronized (mLock) {
                return mNextSeqNumber++;
            }
        }
    }

    /**
     * This API is not generally intended for third party application developers.
     * Use the <a href="{@docRoot}jetpack/androidx.html">AndroidX</a>
     * <a href="{@docRoot}reference/androidx/media2/session/package-summary.html">Media2 session
     * Library</a> for consistent behavior across all devices.
     * <p>
     * Callback to be called for all incoming commands from {@link MediaController2}s.
     */
    public abstract static class SessionCallback {
        /**
         * Called when a controller is created for this session. Return allowed commands for
         * controller. By default it returns {@code null}.
         * <p>
         * You can reject the connection by returning {@code null}. In that case, controller
         * receives {@link MediaController2.ControllerCallback#onDisconnected(MediaController2)}
         * and cannot be used.
         * <p>
         * The controller hasn't connected yet in this method, so calls to the controller
         * (e.g. {@link #sendSessionCommand}) would be ignored. Override {@link #onPostConnect} for
         * the custom initialization for the controller instead.
         *
         * @param session the session for this event
         * @param controller controller information.
         * @return allowed commands. Can be {@code null} to reject connection.
         */
        @Nullable
        public Session2CommandGroup onConnect(@NonNull MediaSession2 session,
                @NonNull ControllerInfo controller) {
            return null;
        }

        /**
         * Called immediately after a controller is connected. This is a convenient method to add
         * custom initialization between the session and a controller.
         * <p>
         * Note that calls to the controller (e.g. {@link #sendSessionCommand}) work here but don't
         * work in {@link #onConnect} because the controller hasn't connected yet in
         * {@link #onConnect}.
         *
         * @param session the session for this event
         * @param controller controller information.
         */
        public void onPostConnect(@NonNull MediaSession2 session,
                @NonNull ControllerInfo controller) {
        }

        /**
         * Called when a controller is disconnected
         *
         * @param session the session for this event
         * @param controller controller information
         */
        public void onDisconnected(@NonNull MediaSession2 session,
                @NonNull ControllerInfo controller) {}

        /**
         * Called when a controller sent a session command.
         *
         * @param session the session for this event
         * @param controller controller information
         * @param command the session command
         * @param args optional arguments
         * @return the result for the session command. If {@code null}, RESULT_INFO_SKIPPED
         *         will be sent to the session.
         */
        @Nullable
        public Session2Command.Result onSessionCommand(@NonNull MediaSession2 session,
                @NonNull ControllerInfo controller, @NonNull Session2Command command,
                @Nullable Bundle args) {
            return null;
        }

        /**
         * Called when the command sent to the controller is finished.
         *
         * @param session the session for this event
         * @param controller controller information
         * @param token the token got from {@link MediaSession2#sendSessionCommand}
         * @param command the session command
         * @param result the result of the session command
         */
        public void onCommandResult(@NonNull MediaSession2 session,
                @NonNull ControllerInfo controller, @NonNull Object token,
                @NonNull Session2Command command, @NonNull Session2Command.Result result) {}
    }

    abstract static class ForegroundServiceEventCallback {
        public void onPlaybackActiveChanged(MediaSession2 session, boolean playbackActive) {}
        public void onSessionClosed(MediaSession2 session) {}
    }
}
