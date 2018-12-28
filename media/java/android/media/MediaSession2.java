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
import android.app.PendingIntent;
import android.content.Context;
import android.media.session.MediaSessionManager;
import android.media.session.MediaSessionManager.RemoteUserInfo;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Allows a media app to expose its transport controls and playback information in a process to
 * other processes including the Android framework and other apps.
 * <p>
 * This API is not generally intended for third party application developers.
 * Use the <a href="{@docRoot}tools/extras/support-library.html">Support Library</a>
 * {@link androidx.media2.MediaSession} for consistent behavior across all devices.
 * @hide
 */
public class MediaSession2 implements AutoCloseable {
    static final String TAG = "MediaSession";
    static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

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
    private final MediaSessionManager mSessionManager;

    MediaSession2(Context context, String id, PendingIntent sessionActivity,
            Executor callbackExecutor, SessionCallback callback) {
        mContext = context;
        mSessionId = id;
        mSessionActivity = sessionActivity;
        mCallbackExecutor = callbackExecutor;
        mCallback = callback;
        mSessionStub = new Session2Link(this);
        mSessionToken = new Session2Token(Process.myUid(), TYPE_SESSION, context.getPackageName(),
                mSessionStub);
        mSessionManager = (MediaSessionManager) mContext.getSystemService(
                Context.MEDIA_SESSION_SERVICE);
    }

    @Override
    public void close() throws Exception {
        // TODO: Implement this
    }

    // AML method
    boolean isClosed() {
        // TODO: Implement this
        return true;
    }

    // AML method
    void onConnect(final Controller2Link controller, int seq, Bundle connectionRequest) {
        if (controller == null || connectionRequest == null) {
            return;
        }
        final int uid = Binder.getCallingUid();
        final int callingPid = Binder.getCallingPid();
        final long token = Binder.clearCallingIdentity();
        // Binder.getCallingPid() can be 0 for an oneway call from the remote process.
        // If it's the case, use PID from the ConnectionRequest.
        final int pid = (callingPid != 0) ? callingPid : connectionRequest.getInt(KEY_PID);
        final String pkg = connectionRequest.getString(KEY_PACKAGE_NAME);
        try {
            RemoteUserInfo remoteUserInfo = new RemoteUserInfo(pkg, pid, uid);
            final ControllerInfo controllerInfo = new ControllerInfo(remoteUserInfo,
                    mSessionManager.isTrustedForMediaControl(remoteUserInfo), controller);
            mCallbackExecutor.execute(() -> {
                if (isClosed()) {
                    return;
                }
                controllerInfo.mAllowedCommands =
                        mCallback.onConnect(MediaSession2.this, controllerInfo);
                // Don't reject connection for the request from trusted app.
                // Otherwise server will fail to retrieve session's information to dispatch
                // media keys to.
                boolean accept =
                        controllerInfo.mAllowedCommands != null || controllerInfo.isTrusted();
                if (accept) {
                    if (controllerInfo.mAllowedCommands == null) {
                        // For trusted apps, send non-null allowed commands to keep
                        // connection.
                        controllerInfo.mAllowedCommands = new Session2CommandGroup();
                    }
                    if (DEBUG) {
                        Log.d(TAG, "Accepting connection: " + controllerInfo);
                    }
                    synchronized (mLock) {
                        if (mConnectedControllers.containsKey(controller)) {
                            Log.w(TAG, "Controller " + controllerInfo + " has sent connection"
                                    + " request multiple times");
                        }
                        mConnectedControllers.put(controller, controllerInfo);
                    }
                    // If connection is accepted, notify the current state to the controller.
                    // It's needed because we cannot call synchronous calls between
                    // session/controller.
                    Bundle connectionResult = new Bundle();
                    connectionResult.putParcelable(KEY_SESSION2_STUB, mSessionStub);
                    connectionResult.putParcelable(KEY_ALLOWED_COMMANDS,
                            controllerInfo.mAllowedCommands);

                    // Double check if session is still there, because close() can be called in
                    // another thread.
                    if (isClosed()) {
                        return;
                    }
                    try {
                        controller.notifyConnected(
                                controllerInfo.mNextSeqNumber++, connectionResult);
                    } catch (RuntimeException e) {
                        // Controller may be died prematurely.
                    }
                } else {
                    if (DEBUG) {
                        Log.d(TAG, "Rejecting connection, controllerInfo=" + controllerInfo);
                    }
                    try {
                        controller.notifyDisconnected(controllerInfo.mNextSeqNumber++);
                    } catch (RuntimeException e) {
                        // Controller may be died prematurely.
                    }
                }
            });
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // AML method
    void onDisconnect(final Controller2Link controller, int seq) {
        if (controller == null) {
            return;
        }
        final long token = Binder.clearCallingIdentity();
        try {
            synchronized (mLock) {
                final ControllerInfo controllerInfo = mConnectedControllers.get(controller);
                mCallbackExecutor.execute(() -> {
                    try {
                        controller.notifyDisconnected(controllerInfo.mNextSeqNumber++);
                    } catch (RuntimeException e) {
                        // Controller may be died prematurely.
                    }
                });
                mConnectedControllers.remove(controller);
            }
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    // AML method
    void onSessionCommand(final Controller2Link controller, final int seq,
            final Session2Command command, final Bundle args) {
        // TODO: Implement this
    }

    /**
     * Information of a controller.
     * <p>
     * This API is not generally intended for third party application developers.
     */
    public static final class ControllerInfo {
        private final RemoteUserInfo mRemoteUserInfo;
        private final boolean mIsTrusted;
        private final Controller2Link mControllerBinder;

        @SuppressWarnings("WeakerAccess") /* synthetic access */
        int mNextSeqNumber;
        @SuppressWarnings("WeakerAccess") /* synthetic access */
        Session2CommandGroup mAllowedCommands;

        /**
         * @param remoteUserInfo remote user info
         * @param trusted {@code true} if trusted, {@code false} otherwise
         * @param controllerBinder Controller2Link. Can be {@code null} only when a
         *           MediaBrowserCompat connects to MediaSessionService and ControllerInfo is
         *           needed for SessionCallback#onConnected().
         */
        ControllerInfo(@NonNull RemoteUserInfo remoteUserInfo, boolean trusted,
                @Nullable Controller2Link controllerBinder) {
            mRemoteUserInfo = remoteUserInfo;
            mIsTrusted = trusted;
            mControllerBinder = controllerBinder;
        }

        /**
         * @hide
         */
        public @NonNull RemoteUserInfo getRemoteUserInfo() {
            return mRemoteUserInfo;
        }

        /**
         * @return package name of the controller. Can be
         *         {@link androidx.media.MediaSessionManager.RemoteUserInfo#LEGACY_CONTROLLER} if
         *         the package name cannot be obtained.
         */
        public @NonNull String getPackageName() {
            return mRemoteUserInfo.getPackageName();
        }

        /**
         * @return uid of the controller. Can be a negative value if the uid cannot be obtained.
         */
        public int getUid() {
            return mRemoteUserInfo.getUid();
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
        public boolean equals(Object obj) {
            if (!(obj instanceof ControllerInfo)) {
                return false;
            }
            if (this == obj) {
                return true;
            }
            ControllerInfo other = (ControllerInfo) obj;
            if (mControllerBinder != null || other.mControllerBinder != null) {
                return Objects.equals(mControllerBinder, other.mControllerBinder);
            }
            return mRemoteUserInfo.equals(other.mRemoteUserInfo);
        }

        @Override
        public String toString() {
            return "ControllerInfo {pkg=" + mRemoteUserInfo.getPackageName() + ", uid="
                    + mRemoteUserInfo.getUid() + ", allowedCommands=" + mAllowedCommands + "})";
        }

        @Nullable Controller2Link getControllerBinder() {
            return mControllerBinder;
        }
    }

    /**
     * Callback to be called for all incoming commands from {@link MediaController2}s.
     * <p>
     * This API is not generally intended for third party application developers.
     */
    public abstract static class SessionCallback {
        /**
         * Called when a controller is created for this session. Return allowed commands for
         * controller. By default it allows all connection requests and commands.
         * <p>
         * You can reject the connection by returning {@code null}. In that case, controller
         * receives {@link MediaController2.ControllerCallback#onDisconnected(MediaController2)}
         * and cannot be used.
         *
         * @param session the session for this event
         * @param controller controller information.
         * @return allowed commands. Can be {@code null} to reject connection.
         */
        @Nullable public Session2CommandGroup onConnect(@NonNull MediaSession2 session,
                @NonNull ControllerInfo controller) {
            Session2CommandGroup commands = new Session2CommandGroup.Builder()
                    .addAllPredefinedCommands(Session2Command.COMMAND_VERSION_1)
                    .build();
            return commands;
        }

        /**
         * Called when a controller is disconnected
         *
         * @param session the session for this event
         * @param controller controller information
         */
        public void onDisconnected(@NonNull MediaSession2 session,
                @NonNull ControllerInfo controller) { }

        /**
         * Called when a controller sent a session command.
         */
        public void onSessionCommand(@NonNull MediaSession2 session,
                @NonNull ControllerInfo controller, @NonNull Session2Command command,
                @Nullable Bundle args) {
        }
    }
}

