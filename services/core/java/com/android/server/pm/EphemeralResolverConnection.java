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

import android.app.IInstantAppResolver;
import android.app.InstantAppResolverService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.InstantAppResolveInfo;
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
import com.android.internal.os.TransferPipe;

import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Represents a remote ephemeral resolver. It is responsible for binding to the remote
 * service and handling all interactions in a timely manner.
 * @hide
 */
final class EphemeralResolverConnection implements DeathRecipient {
    private static final String TAG = "PackageManager";
    // This is running in a critical section and the timeout must be sufficiently low
    private static final long BIND_SERVICE_TIMEOUT_MS =
            ("eng".equals(Build.TYPE)) ? 500 : 300;
    private static final long CALL_SERVICE_TIMEOUT_MS =
            ("eng".equals(Build.TYPE)) ? 200 : 100;
    private static final boolean DEBUG_EPHEMERAL = Build.IS_DEBUGGABLE;

    private final Object mLock = new Object();
    private final GetEphemeralResolveInfoCaller mGetEphemeralResolveInfoCaller =
            new GetEphemeralResolveInfoCaller();
    private final ServiceConnection mServiceConnection = new MyServiceConnection();
    private final Context mContext;
    /** Intent used to bind to the service */
    private final Intent mIntent;

    @GuardedBy("mLock")
    private volatile boolean mIsBinding;
    @GuardedBy("mLock")
    private IInstantAppResolver mRemoteInstance;

    public EphemeralResolverConnection(
            Context context, ComponentName componentName, String action) {
        mContext = context;
        mIntent = new Intent(action).setComponent(componentName);
    }

    public final List<InstantAppResolveInfo> getInstantAppResolveInfoList(int hashPrefix[],
            String token) {
        throwIfCalledOnMainThread();
        IInstantAppResolver target = null;
        try {
            target = getRemoteInstanceLazy(token);
            return mGetEphemeralResolveInfoCaller
                    .getEphemeralResolveInfoList(target, hashPrefix, token);
        } catch (RemoteException e) {
        } catch (InterruptedException | TimeoutException e) {
            if (target == null) {
                Slog.w(TAG, "[" + token + "] Timeout! Phase1 binding to instant app resolver");
            } else {
                Slog.w(TAG, "[" + token + "] Timeout! Phase1 resolving instant app");
            }
        } finally {
            synchronized (mLock) {
                mLock.notifyAll();
            }
        }
        return null;
    }

    public final void getInstantAppIntentFilterList(int hashPrefix[], String token,
            String hostName, PhaseTwoCallback callback, Handler callbackHandler,
            final long startTime) {
        final IRemoteCallback remoteCallback = new IRemoteCallback.Stub() {
            @Override
            public void sendResult(Bundle data) throws RemoteException {
                final ArrayList<InstantAppResolveInfo> resolveList =
                        data.getParcelableArrayList(
                                InstantAppResolverService.EXTRA_RESOLVE_INFO);
                callbackHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onPhaseTwoResolved(resolveList, startTime);
                    }
                });
            }
        };
        try {
            getRemoteInstanceLazy(token)
                    .getInstantAppIntentFilterList(hashPrefix, token, hostName, remoteCallback);
        } catch (RemoteException e) {
        } catch (InterruptedException | TimeoutException e) {
            Slog.w(TAG, "[" + token + "] Timeout! Phase2 binding to instant app resolver");
        }
    }

    private IInstantAppResolver getRemoteInstanceLazy(String token)
            throws TimeoutException, InterruptedException {
        synchronized (mLock) {
            if (mRemoteInstance != null) {
                return mRemoteInstance;
            }
            bindLocked(token);
            return mRemoteInstance;
        }
    }

    private void waitForBind(String token) throws TimeoutException, InterruptedException {
        final long startMillis = SystemClock.uptimeMillis();
        while (mIsBinding) {
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

    private void bindLocked(String token) throws TimeoutException, InterruptedException {
        if (DEBUG_EPHEMERAL && mIsBinding && mRemoteInstance == null) {
            Slog.i(TAG, "[" + token + "] Previous bind timed out; waiting for connection");
        }
        try {
            waitForBind(token);
        } catch (TimeoutException e) {
            if (DEBUG_EPHEMERAL) {
                Slog.i(TAG, "[" + token + "] Previous connection never established; rebinding");
            }
            mContext.unbindService(mServiceConnection);
        }
        if (mRemoteInstance != null) {
            return;
        }
        mIsBinding = true;
        if (DEBUG_EPHEMERAL) {
            Slog.v(TAG, "[" + token + "] Binding to instant app resolver");
        }
        boolean wasBound = false;
        try {
            final int flags = Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE;
            wasBound = mContext
                    .bindServiceAsUser(mIntent, mServiceConnection, flags, UserHandle.SYSTEM);
            if (wasBound) {
                waitForBind(token);
            } else {
                Slog.w(TAG, "[" + token + "] Failed to bind to: " + mIntent);
            }
        } finally {
            mIsBinding = wasBound && mRemoteInstance == null;
            mLock.notifyAll();
        }
    }

    private void throwIfCalledOnMainThread() {
        if (Thread.currentThread() == mContext.getMainLooper().getThread()) {
            throw new RuntimeException("Cannot invoke on the main thread");
        }
    }

    @Override
    public void binderDied() {
        if (DEBUG_EPHEMERAL) {
            Slog.d(TAG, "Binder to instant app resolver died");
        }
        synchronized (mLock) {
            handleBinderDiedLocked();
        }
    }

    private void handleBinderDiedLocked() {
        if (mRemoteInstance != null) {
            mRemoteInstance.asBinder().unlinkToDeath(this, 0 /*flags*/);
        }
        mRemoteInstance = null;
    }

    /**
     * Asynchronous callback when results come back from ephemeral resolution phase two.
     */
    public abstract static class PhaseTwoCallback {
        abstract void onPhaseTwoResolved(
                List<InstantAppResolveInfo> instantAppResolveInfoList, long startTime);
    }

    private final class MyServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG_EPHEMERAL) {
                Slog.d(TAG, "Connected to instant app resolver");
            }
            synchronized (mLock) {
                mRemoteInstance = IInstantAppResolver.Stub.asInterface(service);
                mIsBinding = false;
                try {
                    service.linkToDeath(EphemeralResolverConnection.this, 0 /*flags*/);
                } catch (RemoteException e) {
                    handleBinderDiedLocked();
                }
                mLock.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG_EPHEMERAL) {
                Slog.d(TAG, "Disconnected from instant app resolver");
            }
            synchronized (mLock) {
                handleBinderDiedLocked();
            }
        }
    }

    private static final class GetEphemeralResolveInfoCaller
            extends TimedRemoteCaller<List<InstantAppResolveInfo>> {
        private final IRemoteCallback mCallback;

        public GetEphemeralResolveInfoCaller() {
            super(CALL_SERVICE_TIMEOUT_MS);
            mCallback = new IRemoteCallback.Stub() {
                    @Override
                    public void sendResult(Bundle data) throws RemoteException {
                        final ArrayList<InstantAppResolveInfo> resolveList =
                                data.getParcelableArrayList(
                                        InstantAppResolverService.EXTRA_RESOLVE_INFO);
                        int sequence =
                                data.getInt(InstantAppResolverService.EXTRA_SEQUENCE, -1);
                        onRemoteMethodResult(resolveList, sequence);
                    }
            };
        }

        public List<InstantAppResolveInfo> getEphemeralResolveInfoList(
                IInstantAppResolver target, int hashPrefix[], String token)
                        throws RemoteException, TimeoutException {
            final int sequence = onBeforeRemoteCall();
            target.getInstantAppResolveInfoList(hashPrefix, token, sequence, mCallback);
            return getResultTimed(sequence);
        }
    }
}
