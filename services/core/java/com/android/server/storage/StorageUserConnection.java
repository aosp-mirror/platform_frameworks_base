/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.storage;

import static android.service.storage.ExternalStorageService.EXTRA_ERROR;
import static android.service.storage.ExternalStorageService.FLAG_SESSION_ATTRIBUTE_INDEXABLE;
import static android.service.storage.ExternalStorageService.FLAG_SESSION_TYPE_FUSE;

import static com.android.server.storage.StorageSessionController.ExternalStorageServiceException;

import android.annotation.MainThread;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.ParcelableException;
import android.os.RemoteCallback;
import android.os.UserHandle;
import android.os.storage.StorageManagerInternal;
import android.os.storage.StorageVolume;
import android.service.storage.ExternalStorageService;
import android.service.storage.IExternalStorageService;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Controls the lifecycle of the {@link ActiveConnection} to an {@link ExternalStorageService}
 * for a user and manages storage sessions associated with mounted volumes.
 */
public final class StorageUserConnection {
    private static final String TAG = "StorageUserConnection";

    public static final int REMOTE_TIMEOUT_SECONDS = 5;

    private final Object mLock = new Object();
    private final Context mContext;
    private final int mUserId;
    private final StorageSessionController mSessionController;
    private final ActiveConnection mActiveConnection = new ActiveConnection();
    @GuardedBy("mLock") private final Map<String, Session> mSessions = new HashMap<>();

    public StorageUserConnection(Context context, int userId, StorageSessionController controller) {
        mContext = Objects.requireNonNull(context);
        mUserId = Preconditions.checkArgumentNonnegative(userId);
        mSessionController = controller;
    }

    /**
     * Creates and starts a storage {@link Session}.
     *
     * They must also be cleaned up with {@link #removeSession}.
     *
     * @throws IllegalArgumentException if a {@code Session} with {@code sessionId} already exists
     */
    public void startSession(String sessionId, ParcelFileDescriptor pfd, String upperPath,
            String lowerPath) throws ExternalStorageServiceException {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(pfd);
        Objects.requireNonNull(upperPath);
        Objects.requireNonNull(lowerPath);

        prepareRemote();
        synchronized (mLock) {
            Preconditions.checkArgument(!mSessions.containsKey(sessionId));
            Session session = new Session(sessionId, upperPath, lowerPath);
            mSessions.put(sessionId, session);
            mActiveConnection.startSessionLocked(session, pfd);
        }
    }

    /**
     * Notifies Storage Service about volume state changed.
     *
     * @throws ExternalStorageServiceException if failed to notify the Storage Service that
     * {@code StorageVolume} is changed
     */
    public void notifyVolumeStateChanged(String sessionId, StorageVolume vol)
            throws ExternalStorageServiceException {
        Objects.requireNonNull(sessionId);
        Objects.requireNonNull(vol);

        prepareRemote();
        synchronized (mLock) {
            mActiveConnection.notifyVolumeStateChangedLocked(sessionId, vol);
        }
    }

    /**
     * Removes a session without ending it or waiting for exit.
     *
     * This should only be used if the session has certainly been ended because the volume was
     * unmounted or the user running the session has been stopped. Otherwise, wait for session
     * with {@link #waitForExit}.
     **/
    public Session removeSession(String sessionId) {
        synchronized (mLock) {
            return mSessions.remove(sessionId);
        }
    }

    /**
     * Removes a session and waits for exit
     *
     * @throws ExternalStorageServiceException if the session may not have exited
     **/
    public void removeSessionAndWait(String sessionId) throws ExternalStorageServiceException {
        Session session = removeSession(sessionId);
        if (session == null) {
            Slog.i(TAG, "No session found for id: " + sessionId);
            return;
        }

        Slog.i(TAG, "Waiting for session end " + session + " ...");
        prepareRemote();
        synchronized (mLock) {
            mActiveConnection.endSessionLocked(session);
        }
    }

    /** Restarts all available sessions for a user without blocking.
     *
     * Any failures will be ignored.
     **/
    public void resetUserSessions() {
        synchronized (mLock) {
            if (mSessions.isEmpty()) {
                // Nothing to reset if we have no sessions to restart; we typically
                // hit this path if the user was consciously shut down.
                return;
            }
        }
        StorageManagerInternal sm = LocalServices.getService(StorageManagerInternal.class);
        sm.resetUser(mUserId);
    }

    /**
     * Removes all sessions, without waiting.
     */
    public void removeAllSessions() {
        synchronized (mLock) {
            Slog.i(TAG, "Removing  " + mSessions.size() + " sessions for user: " + mUserId + "...");
            mSessions.clear();
        }
    }

    /**
     * Closes the connection to the {@link ExternalStorageService}. The connection will typically
     * be restarted after close.
     */
    public void close() {
        mActiveConnection.close();
    }

    /** Returns all created sessions. */
    public Set<String> getAllSessionIds() {
        synchronized (mLock) {
            return new HashSet<>(mSessions.keySet());
        }
    }

    private void prepareRemote() throws ExternalStorageServiceException {
        try {
            waitForLatch(mActiveConnection.bind(), "remote_prepare_user " + mUserId);
        } catch (IllegalStateException | TimeoutException e) {
            throw new ExternalStorageServiceException("Failed to prepare remote", e);
        }
    }

    private void waitForLatch(CountDownLatch latch, String reason) throws TimeoutException {
        try {
            if (!latch.await(REMOTE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                // TODO(b/140025078): Call ActivityManager ANR API?
                throw new TimeoutException("Latch wait for " + reason + " elapsed");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Latch wait for " + reason + " interrupted");
        }
    }

    private final class ActiveConnection implements AutoCloseable {
        // Lifecycle connection to the external storage service, needed to unbind.
        @GuardedBy("mLock") @Nullable private ServiceConnection mServiceConnection;
        // True if we are connecting, either bound or binding
        // False && mRemote != null means we are connected
        // False && mRemote == null means we are neither connecting nor connected
        @GuardedBy("mLock") @Nullable private boolean mIsConnecting;
        // Binder object representing the external storage service.
        // Non-null indicates we are connected
        @GuardedBy("mLock") @Nullable private IExternalStorageService mRemote;
        // Exception, if any, thrown from #startSessionLocked or #endSessionLocked
        // Local variables cannot be referenced from a lambda expression :( so we
        // save the exception received in the callback here. Since we guard access
        // (and clear the exception state) with the same lock which we hold during
        // the entire transaction, there is no risk of race.
        @GuardedBy("mLock") @Nullable private ParcelableException mLastException;
        // Not guarded by any lock intentionally and non final because we cannot
        // reset latches so need to create a new one after one use
        private CountDownLatch mLatch;

        @Override
        public void close() {
            ServiceConnection oldConnection = null;
            synchronized (mLock) {
                Slog.i(TAG, "Closing connection for user " + mUserId);
                mIsConnecting = false;
                oldConnection = mServiceConnection;
                mServiceConnection = null;
                mRemote = null;
            }

            if (oldConnection != null) {
                try {
                    mContext.unbindService(oldConnection);
                } catch (Exception e) {
                    // Handle IllegalArgumentException that may be thrown if the user is already
                    // stopped when we try to unbind
                    Slog.w(TAG, "Failed to unbind service", e);
                }
            }
        }

        public boolean isActiveLocked(Session session) {
            if (!session.isInitialisedLocked()) {
                Slog.i(TAG, "Session not initialised " + session);
                return false;
            }

            if (mRemote == null) {
                throw new IllegalStateException("Valid session with inactive connection");
            }
            return true;
        }

        public void startSessionLocked(Session session, ParcelFileDescriptor fd)
                throws ExternalStorageServiceException {
            if (!isActiveLocked(session)) {
                try {
                    fd.close();
                } catch (IOException e) {
                    // ignore
                }
                return;
            }

            CountDownLatch latch = new CountDownLatch(1);
            try {
                mRemote.startSession(session.sessionId,
                        FLAG_SESSION_TYPE_FUSE | FLAG_SESSION_ATTRIBUTE_INDEXABLE,
                        fd, session.upperPath, session.lowerPath, new RemoteCallback(result ->
                                setResultLocked(latch, result)));
                waitForLatch(latch, "start_session " + session);
                maybeThrowExceptionLocked();
            } catch (Exception e) {
                throw new ExternalStorageServiceException("Failed to start session: " + session, e);
            } finally {
                try {
                    fd.close();
                } catch (IOException e) {
                    // Ignore
                }
            }
        }

        public void endSessionLocked(Session session) throws ExternalStorageServiceException {
            if (!isActiveLocked(session)) {
                // Nothing to end, not started yet
                return;
            }

            CountDownLatch latch = new CountDownLatch(1);
            try {
                mRemote.endSession(session.sessionId, new RemoteCallback(result ->
                        setResultLocked(latch, result)));
                waitForLatch(latch, "end_session " + session);
                maybeThrowExceptionLocked();
            } catch (Exception e) {
                throw new ExternalStorageServiceException("Failed to end session: " + session, e);
            }
        }

        public void notifyVolumeStateChangedLocked(String sessionId, StorageVolume vol) throws
                ExternalStorageServiceException {
            CountDownLatch latch = new CountDownLatch(1);
            try {
                mRemote.notifyVolumeStateChanged(sessionId, vol, new RemoteCallback(
                        result -> setResultLocked(latch, result)));
                waitForLatch(latch, "notify_volume_state_changed " + vol);
                maybeThrowExceptionLocked();
            } catch (Exception e) {
                throw new ExternalStorageServiceException("Failed to notify volume state changed "
                        + "for vol : " + vol, e);
            }
        }

        private void setResultLocked(CountDownLatch latch, Bundle result) {
            mLastException = result.getParcelable(EXTRA_ERROR);
            latch.countDown();
        }

        private void maybeThrowExceptionLocked() throws IOException {
            if (mLastException != null) {
                ParcelableException lastException = mLastException;
                mLastException = null;
                try {
                    lastException.maybeRethrow(IOException.class);
                } catch (IOException e) {
                    throw e;
                }
                throw new RuntimeException(lastException);
            }
        }

        public CountDownLatch bind() throws ExternalStorageServiceException {
            ComponentName name = mSessionController.getExternalStorageServiceComponentName();
            if (name == null) {
                // Not ready to bind
                throw new ExternalStorageServiceException(
                        "Not ready to bind to the ExternalStorageService for user " + mUserId);
            }

            synchronized (mLock) {
                if (mRemote != null || mIsConnecting) {
                    // Connected or connecting (bound or binding)
                    // Will wait on a latch that will countdown when we connect, unless we are
                    // connected and the latch has already countdown, yay!
                    return mLatch;
                } // else neither connected nor connecting

                mLatch = new CountDownLatch(1);
                mIsConnecting = true;
                mServiceConnection = new ServiceConnection() {
                    @Override
                    public void onServiceConnected(ComponentName name, IBinder service) {
                        Slog.i(TAG, "Service: [" + name + "] connected. User [" + mUserId + "]");
                        handleConnection(service);
                    }

                    @Override
                    @MainThread
                    public void onServiceDisconnected(ComponentName name) {
                        // Service crashed or process was killed, #onServiceConnected will be called
                        // Don't need to re-bind.
                        Slog.i(TAG, "Service: [" + name + "] disconnected. User [" + mUserId + "]");
                        handleDisconnection();
                    }

                    @Override
                    public void onBindingDied(ComponentName name) {
                        // Application hosting service probably got updated
                        // Need to re-bind.
                        Slog.i(TAG, "Service: [" + name + "] died. User [" + mUserId + "]");
                        handleDisconnection();
                    }

                    @Override
                    public void onNullBinding(ComponentName name) {
                        Slog.wtf(TAG, "Service: [" + name + "] is null. User [" + mUserId + "]");
                    }

                    private void handleConnection(IBinder service) {
                        synchronized (mLock) {
                            if (mIsConnecting) {
                                mRemote = IExternalStorageService.Stub.asInterface(service);
                                mIsConnecting = false;
                                mLatch.countDown();
                                // Separate thread so we don't block the main thead
                                return;
                            }
                        }
                        Slog.wtf(TAG, "Connection closed to the ExternalStorageService for user "
                                + mUserId);
                    }

                    private void handleDisconnection() {
                        // Clear all sessions because we will need a new device fd since
                        // StorageManagerService will reset the device mount state and #startSession
                        // will be called for any required mounts.
                        // Notify StorageManagerService so it can restart all necessary sessions
                        close();
                        resetUserSessions();
                    }
                };
            }

            Slog.i(TAG, "Binding to the ExternalStorageService for user " + mUserId);
            if (mContext.bindServiceAsUser(new Intent().setComponent(name), mServiceConnection,
                            Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT,
                            UserHandle.of(mUserId))) {
                Slog.i(TAG, "Bound to the ExternalStorageService for user " + mUserId);
                return mLatch;
            } else {
                synchronized (mLock) {
                    mIsConnecting = false;
                }
                throw new ExternalStorageServiceException(
                        "Failed to bind to the ExternalStorageService for user " + mUserId);
            }
        }
    }

    private static final class Session {
        public final String sessionId;
        public final String lowerPath;
        public final String upperPath;

        Session(String sessionId, String upperPath, String lowerPath) {
            this.sessionId = sessionId;
            this.upperPath = upperPath;
            this.lowerPath = lowerPath;
        }

        @Override
        public String toString() {
            return "[SessionId: " + sessionId + ". UpperPath: " + upperPath + ". LowerPath: "
                    + lowerPath + "]";
        }

        @GuardedBy("mLock")
        public boolean isInitialisedLocked() {
            return !TextUtils.isEmpty(upperPath) && !TextUtils.isEmpty(lowerPath);
        }
    }
}
