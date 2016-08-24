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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.EphemeralResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.IRemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.TimedRemoteCaller;

import com.android.internal.app.EphemeralResolverService;
import com.android.internal.app.IEphemeralResolver;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * Represents a remote ephemeral resolver. It is responsible for binding to the remote
 * service and handling all interactions in a timely manner.
 * @hide
 */
final class EphemeralResolverConnection {
    // This is running in a critical section and the timeout must be sufficiently low
    private static final long BIND_SERVICE_TIMEOUT_MS =
            ("eng".equals(Build.TYPE)) ? 300 : 200;

    private final Object mLock = new Object();
    private final GetEphemeralResolveInfoCaller mGetEphemeralResolveInfoCaller =
            new GetEphemeralResolveInfoCaller();
    private final ServiceConnection mServiceConnection = new MyServiceConnection();
    private final Context mContext;
    /** Intent used to bind to the service */
    private final Intent mIntent;

    private IEphemeralResolver mRemoteInstance;

    public EphemeralResolverConnection(Context context, ComponentName componentName) {
        mContext = context;
        mIntent = new Intent().setComponent(componentName);
    }

    public final List<EphemeralResolveInfo> getEphemeralResolveInfoList(int hashPrefix) {
        throwIfCalledOnMainThread();
        try {
            return mGetEphemeralResolveInfoCaller.getEphemeralResolveInfoList(
                    getRemoteInstanceLazy(), hashPrefix);
        } catch (RemoteException re) {
        } catch (TimeoutException te) {
        } finally {
            synchronized (mLock) {
                mLock.notifyAll();
            }
        }
        return null;
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String prefix) {
        synchronized (mLock) {
            pw.append(prefix).append("bound=")
                    .append((mRemoteInstance != null) ? "true" : "false").println();

            pw.flush();

            try {
                getRemoteInstanceLazy().asBinder().dump(fd, new String[] { prefix });
            } catch (TimeoutException te) {
                /* ignore */
            } catch (RemoteException re) {
                /* ignore */
            }
        }
    }

    private IEphemeralResolver getRemoteInstanceLazy() throws TimeoutException {
        synchronized (mLock) {
            if (mRemoteInstance != null) {
                return mRemoteInstance;
            }
            bindLocked();
            return mRemoteInstance;
        }
    }

    private void bindLocked() throws TimeoutException {
        if (mRemoteInstance != null) {
            return;
        }

        mContext.bindServiceAsUser(mIntent, mServiceConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE, UserHandle.SYSTEM);

        final long startMillis = SystemClock.uptimeMillis();
        while (true) {
            if (mRemoteInstance != null) {
                break;
            }
            final long elapsedMillis = SystemClock.uptimeMillis() - startMillis;
            final long remainingMillis = BIND_SERVICE_TIMEOUT_MS - elapsedMillis;
            if (remainingMillis <= 0) {
                throw new TimeoutException("Didn't bind to resolver in time.");
            }
            try {
                mLock.wait(remainingMillis);
            } catch (InterruptedException ie) {
                /* ignore */
            }
        }

        mLock.notifyAll();
    }

    private void throwIfCalledOnMainThread() {
        if (Thread.currentThread() == mContext.getMainLooper().getThread()) {
            throw new RuntimeException("Cannot invoke on the main thread");
        }
    }

    private final class MyServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                mRemoteInstance = IEphemeralResolver.Stub.asInterface(service);
                mLock.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                mRemoteInstance = null;
            }
        }
    }

    private static final class GetEphemeralResolveInfoCaller
            extends TimedRemoteCaller<List<EphemeralResolveInfo>> {
        private final IRemoteCallback mCallback;

        public GetEphemeralResolveInfoCaller() {
            super(TimedRemoteCaller.DEFAULT_CALL_TIMEOUT_MILLIS);
            mCallback = new IRemoteCallback.Stub() {
                    @Override
                    public void sendResult(Bundle data) throws RemoteException {
                        final ArrayList<EphemeralResolveInfo> resolveList =
                                data.getParcelableArrayList(
                                        EphemeralResolverService.EXTRA_RESOLVE_INFO);
                        int sequence =
                                data.getInt(EphemeralResolverService.EXTRA_SEQUENCE, -1);
                        onRemoteMethodResult(resolveList, sequence);
                    }
            };
        }

        public List<EphemeralResolveInfo> getEphemeralResolveInfoList(
                IEphemeralResolver target, int hashPrefix)
                        throws RemoteException, TimeoutException {
            final int sequence = onBeforeRemoteCall();
            target.getEphemeralResolveInfoList(mCallback, hashPrefix, sequence);
            return getResultTimed(sequence);
        }
    }
}
