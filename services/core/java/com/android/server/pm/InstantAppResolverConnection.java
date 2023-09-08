/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.server.pm;

import android.annotation.AnyThread;
import android.annotation.WorkerThread;
import android.app.IInstantAppResolver;
import android.app.InstantAppResolverService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.InstantAppRequestInfo;
import android.content.pm.InstantAppResolveInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Slog;
import android.util.TimedRemoteCaller;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BackgroundThread;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeoutException;

/**
 * Represents a remote instant app resolver. It is responsible for binding to the remote
 * service and handling all interactions in a timely manner.
 * @hide
 */
final class InstantAppResolverConnection implements DeathRecipient {
    private static final String TAG = "PackageManager";
    // This is running in a critical section and the timeout must be sufficiently low
    private static final long BIND_SERVICE_TIMEOUT_MS =
            Build.IS_ENG ? 500 : 300;
    private static final long CALL_SERVICE_TIMEOUT_MS =
            Build.IS_ENG ? 200 : 100;
    private static final boolean DEBUG_INSTANT = Build.IS_DEBUGGABLE;

    private final Object mLock = new Object();
    private final GetInstantAppResolveInfoCaller mGetInstantAppResolveInfoCaller =
            new GetInstantAppResolveInfoCaller();
    private final ServiceConnection mServiceConnection = new MyServiceConnection();
    private final Context mContext;
    /** Intent used to bind to the service */
    private final Intent mIntent;

    private static final int STATE_IDLE    = 0; // no bind operation is ongoing
    private static final int STATE_BINDING = 1; // someone is binding and waiting
    private static final int STATE_PENDING = 2; // a bind is pending, but the caller is not waiting
    private final Handler mBgHandler;

    @GuardedBy("mLock")
    private int mBindState = STATE_IDLE;
    @GuardedBy("mLock")
    private IInstantAppResolver mRemoteInstance;

    public InstantAppResolverConnection(
            Context context, ComponentName componentName, String action) {
        mContext = context;
        mIntent = new Intent(action).setComponent(componentName);
        mBgHandler = BackgroundThread.getHandler();
    }

    public List<InstantAppResolveInfo> getInstantAppResolveInfoList(InstantAppRequestInfo request)
            throws ConnectionException {
        throwIfCalledOnMainThread();
        IInstantAppResolver target = null;
        try {
            try {
                target = getRemoteInstanceLazy(request.getToken());
            } catch (TimeoutException e) {
                throw new ConnectionException(ConnectionException.FAILURE_BIND);
            } catch (InterruptedException e) {
                throw new ConnectionException(ConnectionException.FAILURE_INTERRUPTED);
            }
            try {
                return mGetInstantAppResolveInfoCaller
                        .getInstantAppResolveInfoList(target, request);
            } catch (TimeoutException e) {
                throw new ConnectionException(ConnectionException.FAILURE_CALL);
            } catch (RemoteException ignore) {
            }
        } finally {
            synchronized (mLock) {
                mLock.notifyAll();
            }
        }
        return null;
    }

    public void getInstantAppIntentFilterList(InstantAppRequestInfo request,
            PhaseTwoCallback callback, Handler callbackHandler, final long startTime)
            throws ConnectionException {
        final IRemoteCallback remoteCallback = new IRemoteCallback.Stub() {
            @Override
            public void sendResult(Bundle data) throws RemoteException {
                final ArrayList<InstantAppResolveInfo> resolveList =
                        data.getParcelableArrayList(
                                InstantAppResolverService.EXTRA_RESOLVE_INFO, android.content.pm.InstantAppResolveInfo.class);
                callbackHandler.post(() -> callback.onPhaseTwoResolved(resolveList, startTime));
            }
        };
        try {
            getRemoteInstanceLazy(request.getToken())
                    .getInstantAppIntentFilterList(request, remoteCallback);
        } catch (TimeoutException e) {
            throw new ConnectionException(ConnectionException.FAILURE_BIND);
        } catch (InterruptedException e) {
            throw new ConnectionException(ConnectionException.FAILURE_INTERRUPTED);
        } catch (RemoteException ignore) {
        }
    }

    @WorkerThread
    private IInstantAppResolver getRemoteInstanceLazy(String token)
            throws ConnectionException, TimeoutException, InterruptedException {
        final long binderToken = Binder.clearCallingIdentity();
        try {
            return bind(token);
        } finally {
            Binder.restoreCallingIdentity(binderToken);
        }
    }

    @GuardedBy("mLock")
    private void waitForBindLocked(String token) throws TimeoutException, InterruptedException {
        final long startMillis = SystemClock.uptimeMillis();
        while (mBindState != STATE_IDLE) {
            if (mRemoteInstance != null) {
                break;
            }
            final long elapsedMillis = SystemClock.uptimeMillis() - startMillis;
            final long remainingMillis = BIND_SERVICE_TIMEOUT_MS - elapsedMillis;
            if (remainingMillis <= 0) {
                throw new TimeoutException("[" + token + "] Didn't bind to resolver in time!");
            }
            mLock.wait(remainingMillis);
        }
    }

    @WorkerThread
    private IInstantAppResolver bind(String token)
            throws ConnectionException, TimeoutException, InterruptedException {
        boolean doUnbind = false;
        synchronized (mLock) {
            if (mRemoteInstance != null) {
                return mRemoteInstance;
            }

            if (mBindState == STATE_PENDING) {
                // there is a pending bind, let's see if we can use it.
                if (DEBUG_INSTANT) {
                    Slog.i(TAG, "[" + token + "] Previous bind timed out; waiting for connection");
                }
                try {
                    waitForBindLocked(token);
                    if (mRemoteInstance != null) {
                        return mRemoteInstance;
                    }
                } catch (TimeoutException e) {
                    // nope, we might have to try a rebind.
                    doUnbind = true;
                }
            }

            if (mBindState == STATE_BINDING) {
                // someone was binding when we called bind(), or they raced ahead while we were
                // waiting in the PENDING case; wait for their result instead. Last chance!
                if (DEBUG_INSTANT) {
                    Slog.i(TAG, "[" + token + "] Another thread is binding; waiting for connection");
                }
                waitForBindLocked(token);
                // if the other thread's bindService() returned false, we could still have null.
                if (mRemoteInstance != null) {
                    return mRemoteInstance;
                }
                throw new ConnectionException(ConnectionException.FAILURE_BIND);
            }
            mBindState = STATE_BINDING; // our time to shine! :)
        }

        // only one thread can be here at a time (the one that set STATE_BINDING)
        boolean wasBound = false;
        IInstantAppResolver instance = null;
        try {
            if (doUnbind) {
                if (DEBUG_INSTANT) {
                    Slog.i(TAG, "[" + token + "] Previous connection never established; rebinding");
                }
                try {
                    mContext.unbindService(mServiceConnection);
                } catch (Exception e) {
                    Slog.e(TAG, "[" + token + "] Service already unbound", e);
                }

            }
            if (DEBUG_INSTANT) {
                Slog.v(TAG, "[" + token + "] Binding to instant app resolver");
            }
            final int flags = Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE;
            wasBound = mContext
                    .bindServiceAsUser(mIntent, mServiceConnection, flags, UserHandle.SYSTEM);
            if (wasBound) {
                synchronized (mLock) {
                    waitForBindLocked(token);
                    instance = mRemoteInstance;
                    return instance;
                }
            } else {
                Slog.w(TAG, "[" + token + "] Failed to bind to: " + mIntent);
                throw new ConnectionException(ConnectionException.FAILURE_BIND);
            }
        } finally {
            synchronized (mLock) {
                if (wasBound && instance == null) {
                    mBindState = STATE_PENDING;
                } else {
                    mBindState = STATE_IDLE;
                }
                mLock.notifyAll();
            }
        }
    }

    private void throwIfCalledOnMainThread() {
        if (Thread.currentThread() == mContext.getMainLooper().getThread()) {
            throw new RuntimeException("Cannot invoke on the main thread");
        }
    }

    @AnyThread
    void optimisticBind() {
        mBgHandler.post(() -> {
            try {
                if (bind("Optimistic Bind") != null && DEBUG_INSTANT) {
                    Slog.i(TAG, "Optimistic bind succeeded.");
                }
            } catch (ConnectionException | TimeoutException | InterruptedException e) {
                Slog.e(TAG, "Optimistic bind failed.", e);
            }
        });
    }

    @Override
    public void binderDied() {
        if (DEBUG_INSTANT) {
            Slog.d(TAG, "Binder to instant app resolver died");
        }
        synchronized (mLock) {
            handleBinderDiedLocked();
        }
        optimisticBind();
    }

    @GuardedBy("mLock")
    private void handleBinderDiedLocked() {
        if (mRemoteInstance != null) {
            try {
                mRemoteInstance.asBinder().unlinkToDeath(this, 0 /*flags*/);
            } catch (NoSuchElementException ignore) { }
        }
        mRemoteInstance = null;

        try {
            mContext.unbindService(mServiceConnection);
        } catch (Exception ignored) {
        }
    }

    /**
     * Asynchronous callback when results come back from ephemeral resolution phase two.
     */
    public abstract static class PhaseTwoCallback {
        abstract void onPhaseTwoResolved(
                List<InstantAppResolveInfo> instantAppResolveInfoList, long startTime);
    }

    public static class ConnectionException extends Exception {
        public static final int FAILURE_BIND = 1;
        public static final int FAILURE_CALL = 2;
        public static final int FAILURE_INTERRUPTED = 3;

        public final int failure;
        public ConnectionException(int _failure) {
            failure = _failure;
        }
    }

    private final class MyServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG_INSTANT) {
                Slog.d(TAG, "Connected to instant app resolver");
            }
            synchronized (mLock) {
                mRemoteInstance = IInstantAppResolver.Stub.asInterface(service);
                if (mBindState == STATE_PENDING) {
                    mBindState = STATE_IDLE;
                }
                try {
                    service.linkToDeath(InstantAppResolverConnection.this, 0 /*flags*/);
                } catch (RemoteException e) {
                    handleBinderDiedLocked();
                }
                mLock.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG_INSTANT) {
                Slog.d(TAG, "Disconnected from instant app resolver");
            }
            synchronized (mLock) {
                handleBinderDiedLocked();
            }
        }
    }

    private static final class GetInstantAppResolveInfoCaller
            extends TimedRemoteCaller<List<InstantAppResolveInfo>> {
        private final IRemoteCallback mCallback;

        public GetInstantAppResolveInfoCaller() {
            super(CALL_SERVICE_TIMEOUT_MS);
            mCallback = new IRemoteCallback.Stub() {
                    @Override
                    public void sendResult(Bundle data) throws RemoteException {
                        final ArrayList<InstantAppResolveInfo> resolveList =
                                data.getParcelableArrayList(
                                        InstantAppResolverService.EXTRA_RESOLVE_INFO, android.content.pm.InstantAppResolveInfo.class);
                        int sequence =
                                data.getInt(InstantAppResolverService.EXTRA_SEQUENCE, -1);
                        onRemoteMethodResult(resolveList, sequence);
                    }
            };
        }

        public List<InstantAppResolveInfo> getInstantAppResolveInfoList(IInstantAppResolver target,
                InstantAppRequestInfo request) throws RemoteException, TimeoutException {
            final int sequence = onBeforeRemoteCall();
            target.getInstantAppResolveInfoList(request, sequence, mCallback);
            return getResultTimed(sequence);
        }
    }
}
