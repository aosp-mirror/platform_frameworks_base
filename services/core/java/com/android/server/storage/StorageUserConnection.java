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
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.ParcelableException;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.storage.StorageManagerInternal;
import android.os.storage.StorageVolume;
import android.service.storage.ExternalStorageService;
import android.service.storage.IExternalStorageService;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;
import com.android.server.LocalServices;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Controls the lifecycle of the {@link ActiveConnection} to an {@link ExternalStorageService}
 * for a user and manages storage sessions associated with mounted volumes.
 */
public final class StorageUserConnection {
    private static final String TAG = "StorageUserConnection";

    private static final int DEFAULT_REMOTE_TIMEOUT_SECONDS = 20;

    private final Object mSessionsLock = new Object();
    private final Context mContext;
    private final int mUserId;
    private final StorageSessionController mSessionController;
    private final ActiveConnection mActiveConnection = new ActiveConnection();
    @GuardedBy("mLock") private final Map<String, Session> mSessions = new HashMap<>();
    private final HandlerThread mHandlerThread;

    public StorageUserConnection(Context context, int userId, StorageSessionController controller) {
        mContext = Objects.requireNonNull(context);
        mUserId = Preconditions.checkArgumentNonnegative(userId);
        mSessionController = controller;
        mHandlerThread = new HandlerThread("StorageUserConnectionThread-" + mUserId);
        mHandlerThread.start();
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

        Session session = new Session(sessionId, upperPath, lowerPath);
        synchronized (mSessionsLock) {
            Preconditions.checkArgument(!mSessions.containsKey(sessionId));
            mSessions.put(sessionId, session);
        }
        mActiveConnection.startSession(session, pfd);
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

        synchronized (mSessionsLock) {
            if (!mSessions.containsKey(sessionId)) {
                Slog.i(TAG, "No session found for sessionId: " + sessionId);
                return;
            }
        }
        mActiveConnection.notifyVolumeStateChanged(sessionId, vol);
    }

    /**
     * Removes a session without ending it or waiting for exit.
     *
     * This should only be used if the session has certainly been ended because the volume was
     * unmounted or the user running the session has been stopped. Otherwise, wait for session
     * with {@link #waitForExit}.
     **/
    public Session removeSession(String sessionId) {
        synchronized (mSessionsLock) {
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
        mActiveConnection.endSession(session);
    }

    /** Restarts all available sessions for a user without blocking.
     *
     * Any failures will be ignored.
     **/
    public void resetUserSessions() {
        synchronized (mSessionsLock) {
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
        synchronized (mSessionsLock) {
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
        mHandlerThread.quit();
    }

    /** Returns all created sessions. */
    public Set<String> getAllSessionIds() {
        synchronized (mSessionsLock) {
            return new HashSet<>(mSessions.keySet());
        }
    }

    @FunctionalInterface
    interface AsyncStorageServiceCall {
        void run(@NonNull IExternalStorageService service, RemoteCallback callback) throws
                RemoteException;
    }

    private final class ActiveConnection implements AutoCloseable {
        private final Object mLock = new Object();

        // Lifecycle connection to the external storage service, needed to unbind.
        @GuardedBy("mLock") @Nullable private ServiceConnection mServiceConnection;

        // A future that holds the remote interface
        @GuardedBy("mLock")
        @Nullable private CompletableFuture<IExternalStorageService> mRemoteFuture;

        // A list of outstanding futures for async calls, for which we are still waiting
        // for a callback. Used to unblock waiters if the service dies.
        @GuardedBy("mLock")
        private ArrayList<CompletableFuture<Void>> mOutstandingOps = new ArrayList<>();

        @Override
        public void close() {
            ServiceConnection oldConnection = null;
            synchronized (mLock) {
                Slog.i(TAG, "Closing connection for user " + mUserId);
                oldConnection = mServiceConnection;
                mServiceConnection = null;
                if (mRemoteFuture != null) {
                    // Let folks who are waiting for the connection know it ain't gonna happen
                    mRemoteFuture.cancel(true);
                    mRemoteFuture = null;
                }
                // Let folks waiting for callbacks from the remote know it ain't gonna happen
                for (CompletableFuture<Void> op : mOutstandingOps) {
                    op.cancel(true);
                }
                mOutstandingOps.clear();
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

        private void waitForAsync(AsyncStorageServiceCall asyncCall) throws Exception {
            CompletableFuture<IExternalStorageService> serviceFuture = connectIfNeeded();
            CompletableFuture<Void> opFuture = new CompletableFuture<>();

            try {
                synchronized (mLock) {
                    mOutstandingOps.add(opFuture);
                }
                serviceFuture.thenCompose(service -> {
                    try {
                        asyncCall.run(service,
                                new RemoteCallback(result -> setResult(result, opFuture)));
                    } catch (RemoteException e) {
                        opFuture.completeExceptionally(e);
                    }

                    return opFuture;
                }).get(DEFAULT_REMOTE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } finally {
                synchronized (mLock) {
                    mOutstandingOps.remove(opFuture);
                }
            }
        }

        public void startSession(Session session, ParcelFileDescriptor fd)
                throws ExternalStorageServiceException {
            try {
                waitForAsync((service, callback) -> service.startSession(session.sessionId,
                        FLAG_SESSION_TYPE_FUSE | FLAG_SESSION_ATTRIBUTE_INDEXABLE,
                        fd, session.upperPath, session.lowerPath, callback));
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

        public void endSession(Session session) throws ExternalStorageServiceException {
            try {
                waitForAsync((service, callback) ->
                        service.endSession(session.sessionId, callback));
            } catch (Exception e) {
                throw new ExternalStorageServiceException("Failed to end session: " + session, e);
            }
        }


        public void notifyVolumeStateChanged(String sessionId, StorageVolume vol) throws
                ExternalStorageServiceException {
            try {
                waitForAsync((service, callback) ->
                        service.notifyVolumeStateChanged(sessionId, vol, callback));
            } catch (Exception e) {
                throw new ExternalStorageServiceException("Failed to notify volume state changed "
                        + "for vol : " + vol, e);
            }
        }

        private void setResult(Bundle result, CompletableFuture<Void> future) {
            ParcelableException ex = result.getParcelable(EXTRA_ERROR);
            if (ex != null) {
                future.completeExceptionally(ex);
            } else {
                future.complete(null);
            }
        }

        private CompletableFuture<IExternalStorageService> connectIfNeeded() throws
                ExternalStorageServiceException {
            ComponentName name = mSessionController.getExternalStorageServiceComponentName();
            if (name == null) {
                // Not ready to bind
                throw new ExternalStorageServiceException(
                        "Not ready to bind to the ExternalStorageService for user " + mUserId);
            }
            synchronized (mLock) {
                if (mRemoteFuture != null) {
                    return mRemoteFuture;
                }
                CompletableFuture<IExternalStorageService> future = new CompletableFuture<>();
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
                            future.complete(
                                    IExternalStorageService.Stub.asInterface(service));
                        }
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

                Slog.i(TAG, "Binding to the ExternalStorageService for user " + mUserId);
                // Schedule on a worker thread, because the system server main thread can be
                // very busy early in boot.
                if (mContext.bindServiceAsUser(new Intent().setComponent(name),
                                mServiceConnection,
                                Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT,
                                mHandlerThread.getThreadHandler(),
                                UserHandle.of(mUserId))) {
                    Slog.i(TAG, "Bound to the ExternalStorageService for user " + mUserId);
                    mRemoteFuture = future;
                    return future;
                } else {
                    throw new ExternalStorageServiceException(
                            "Failed to bind to the ExternalStorageService for user " + mUserId);
                }
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
    }
}
