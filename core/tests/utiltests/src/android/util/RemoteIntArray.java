/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.util;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.support.test.InstrumentationRegistry;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.TimeoutException;

final class RemoteIntArray implements ServiceConnection, Closeable {
    private static final long BIND_REMOTE_SERVICE_TIMEOUT =
            ("eng".equals(Build.TYPE)) ? 120000 : 10000;

    private final Object mLock = new Object();

    private final Intent mIntent = new Intent();

    private android.util.IRemoteMemoryIntArray mRemoteInstance;

    public RemoteIntArray(int size, boolean clientWritable) throws IOException, TimeoutException {
        mIntent.setComponent(new ComponentName(InstrumentationRegistry.getContext(),
                RemoteMemoryIntArrayService.class));
        synchronized (mLock) {
            if (mRemoteInstance == null) {
                bindLocked();
            }
            try {
                mRemoteInstance.create(size, clientWritable);
            } catch (RemoteException e) {
                throw new IOException(e);
            }
        }
    }

    public MemoryIntArray peekInstance() {
        try {
            return mRemoteInstance.peekInstance();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isWritable() {
        try {
            return mRemoteInstance.isWritable();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public int get(int index) throws IOException {
        try {
            return mRemoteInstance.get(index);
        } catch (RemoteException e) {
            throw new IOException(e);
        }
    }

    public void set(int index, int value) throws IOException {
        try {
            mRemoteInstance.set(index, value);
        } catch (RemoteException e) {
            throw new IOException(e);
        }
    }

    public int size() throws IOException {
        try {
            return mRemoteInstance.size();
        } catch (RemoteException e) {
            throw new IOException(e);
        }
    }

    public void close() {
        try {
            mRemoteInstance.close();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean isClosed() {
        try {
            return mRemoteInstance.isClosed();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    private void bindLocked() throws TimeoutException {
        if (mRemoteInstance != null) {
            return;
        }

        InstrumentationRegistry.getContext().bindService(mIntent, this, Context.BIND_AUTO_CREATE);

        final long startMillis = SystemClock.uptimeMillis();
        while (true) {
            if (mRemoteInstance != null) {
                break;
            }
            final long elapsedMillis = SystemClock.uptimeMillis() - startMillis;
            final long remainingMillis = BIND_REMOTE_SERVICE_TIMEOUT - elapsedMillis;
            if (remainingMillis <= 0) {
                throw new TimeoutException("Cannot get spooler!");
            }
            try {
                mLock.wait(remainingMillis);
            } catch (InterruptedException ie) {
                    /* ignore */
            }
        }

        mLock.notifyAll();
    }

    public void destroy() {
        synchronized (mLock) {
            if (mRemoteInstance == null) {
                return;
            }
            mRemoteInstance = null;
            InstrumentationRegistry.getContext().unbindService(this);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        synchronized (mLock) {
            mRemoteInstance = android.util.IRemoteMemoryIntArray.Stub.asInterface(service);
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