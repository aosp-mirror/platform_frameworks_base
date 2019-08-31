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
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.storage.VolumeInfo;
import android.service.storage.ExternalStorageService;
import android.service.storage.IExternalStorageService;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Controls the lifecycle of the {@link ActiveConnection} to an {@link ExternalStorageService}
 * for a user and manages storage sessions represented by a {@link Session}.
 */
public final class StorageUserConnection {
    private static final String TAG = "StorageUserConnection";

    private final Object mLock = new Object();
    private final Context mContext;
    private final int mUserId;
    private final StorageSessionController mSessionController;
    private final ActiveConnection mActiveConnection = new ActiveConnection();
    @GuardedBy("mLock") private final Map<String, Session> mSessions = new HashMap<>();

    public StorageUserConnection(Context context, int userId, StorageSessionController controller) {
        mContext = Preconditions.checkNotNull(context);
        mUserId = Preconditions.checkArgumentNonnegative(userId);
        mSessionController = controller;
    }

    /** Starts a session for a user */
    public void startSession(FileDescriptor deviceFd, VolumeInfo vol)
            throws ExternalStorageServiceException {
        String sessionId = vol.getId();
        String upperPath = vol.getPath().getPath();
        String lowerPath = vol.getInternalPath().getPath();
        Slog.i(TAG, "Starting session with id: " + sessionId + " and upperPath: " + upperPath
                + " and lowerPath: " + lowerPath);
        Session session = new Session(sessionId, deviceFd, upperPath, lowerPath);
        synchronized (mLock) {
            // TODO(b/135341433): Ensure we don't replace a session without ending the previous
            mSessions.put(sessionId, session);
            // TODO(b/135341433): If this fails, maybe its at boot, how to handle if not boot?
            mActiveConnection.startSessionLocked(session);
        }
    }

    /**
     * Ends a session for a user.
     *
     * @return {@code true} if there are no more sessions for this user, {@code false} otherwise
     **/
    public boolean endSession(VolumeInfo vol) throws ExternalStorageServiceException {
        synchronized (mLock) {
            Session session = mSessions.remove(vol.getId());
            if (session != null) {
                mActiveConnection.endSessionLocked(session);
                mSessions.remove(session.sessionId);
            }
            boolean isAllSessionsEnded = mSessions.isEmpty();
            if (isAllSessionsEnded) {
                mActiveConnection.close();
            }
            return isAllSessionsEnded;
        }
    }

    /** Starts all available sessions for a user */
    public void startAllSessions() throws ExternalStorageServiceException {
        synchronized (mLock) {
            for (Session session : mSessions.values()) {
                mActiveConnection.startSessionLocked(session);
            }
        }
    }

    /** Ends all available sessions for a user */
    public void endAllSessions() throws ExternalStorageServiceException {
        synchronized (mLock) {
            for (Session session : mSessions.values()) {
                mActiveConnection.endSessionLocked(session);
                mSessions.remove(session.sessionId);
            }
            mActiveConnection.close();
        }
    }

    private final class ActiveConnection implements AutoCloseable {
        // Lifecycle connection to the external storage service, needed to unbind.
        // We should only try to bind if mServiceConnection is null.
        // Non-null indicates we are connected or connecting.
        @GuardedBy("mLock") @Nullable private ServiceConnection mServiceConnection;
        // Binder object representing the external storage service.
        // Non-null indicates we are connected
        @GuardedBy("mLock") @Nullable private IExternalStorageService mRemote;
        // Exception, if any, thrown from #startSessionLocked or #endSessionLocked
        // Local variables cannot be referenced from a lambda expression :( so we
        // save the exception received in the callback here. Since we guard access
        // (and clear the exception state) with the same lock which we hold during
        // the entire transaction, there is no risk of race.
        @GuardedBy("mLock") @Nullable private ParcelableException mLastException;

        @Override
        public void close() {
            synchronized (mLock) {
                if (mServiceConnection != null) {
                    mContext.unbindService(mServiceConnection);
                }
                mServiceConnection = null;
                mRemote = null;
            }
        }

        public void startSessionLocked(Session session) throws ExternalStorageServiceException {
            if (mServiceConnection == null || mRemote == null) {
                if (mServiceConnection == null) {
                    // Not bound
                    bindLocked();
                } // else we are binding. In any case when we bind we'll re-start all sessions
                return;
            }

            CountDownLatch latch = new CountDownLatch(1);
            try {
                mRemote.startSession(session.sessionId,
                        FLAG_SESSION_TYPE_FUSE | FLAG_SESSION_ATTRIBUTE_INDEXABLE,
                        new ParcelFileDescriptor(session.deviceFd), session.upperPath,
                        session.lowerPath, new RemoteCallback(result ->
                                setResultLocked(latch, result)));

            } catch (RemoteException e) {
                throw new ExternalStorageServiceException(e);
            }
            waitAndReturnResultLocked(latch);
        }

        public void endSessionLocked(Session session) throws ExternalStorageServiceException {
            if (mRemote == null) {
                // TODO(b/135341433): This assumes if there is no connection, there are no
                // session resources held. Need to document in the ExternalStorageService
                // API that implementors should end all sessions and clean up resources
                // when the binding is lost, onDestroy?
                return;
            }

            CountDownLatch latch = new CountDownLatch(1);
            try {
                mRemote.endSession(session.sessionId, new RemoteCallback(result ->
                                setResultLocked(latch, result)));
            } catch (RemoteException e) {
                throw new ExternalStorageServiceException(e);
            }
            waitAndReturnResultLocked(latch);
        }

        private void setResultLocked(CountDownLatch latch, Bundle result) {
            mLastException = result.getParcelable(EXTRA_ERROR);
            latch.countDown();
        }

        private void waitAndReturnResultLocked(CountDownLatch latch)
                throws ExternalStorageServiceException {
            try {
                // TODO(b/140025078): Call ActivityManager ANR API?
                latch.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while waiting for ExternalStorageService result");
            }
            if (mLastException != null) {
                mLastException = null;
                try {
                    mLastException.maybeRethrow(IOException.class);
                } catch (IOException e) {
                    throw new ExternalStorageServiceException(e);
                }
                throw new RuntimeException(mLastException);
            }
            mLastException = null;
        }

        private void bindLocked() {
            ComponentName name = mSessionController.getExternalStorageServiceComponentName();
            if (name == null) {
                Slog.i(TAG, "Not ready to bind to the ExternalStorageService for user " + mUserId);
                return;
            }

            ServiceConnection connection = new ServiceConnection() {
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
                        // Should never happen. Service returned null from #onBind.
                        Slog.wtf(TAG, "Service: [" + name + "] is null. User [" + mUserId + "]");
                    }

                    private void handleConnection(IBinder service) {
                        synchronized (mLock) {
                            if (mServiceConnection != null) {
                                mRemote = IExternalStorageService.Stub.asInterface(service);
                            } else {
                                Slog.wtf(TAG, "Service connected without a connection object??");
                            }
                        }

                        try {
                            startAllSessions();
                        } catch (ExternalStorageServiceException e) {
                            Slog.e(TAG, "Failed to start all sessions", e);
                        }
                    }

                    private void handleDisconnection() {
                        close();
                        // Clear all sessions because we will need a new device fd since
                        // StorageManagerService will reset the device mount state and #startSession
                        // will be called for any required mounts.
                        synchronized (mLock) {
                            mSessions.clear();
                        }
                        // Notify StorageManagerService so it can restart all necessary sessions
                        mSessionController.getCallback().onUserDisconnected(mUserId);
                    }
                };

            Slog.i(TAG, "Binding to the ExternalStorageService for user " + mUserId);
            // TODO(b/135341433): Verify required service flags BIND_IMPORTANT?
            if (mContext.bindServiceAsUser(new Intent().setComponent(name), connection,
                            Context.BIND_AUTO_CREATE, UserHandle.of(mUserId))) {
                Slog.i(TAG, "Bound to the ExternalStorageService for user " + mUserId);
                mServiceConnection = connection;
                // Reset the remote, we will set when we connect
                mRemote = null;
            } else {
                Slog.w(TAG, "Failed to bind to the ExternalStorageService for user " + mUserId);
            }
        }
    }

    private static final class Session {
        public final String sessionId;
        public final FileDescriptor deviceFd;
        public final String lowerPath;
        public final String upperPath;

        Session(String sessionId, FileDescriptor deviceFd, String upperPath,
                String lowerPath) {
            this.sessionId = sessionId;
            this.upperPath = upperPath;
            this.lowerPath = lowerPath;
            this.deviceFd = deviceFd;
        }
    }
}
