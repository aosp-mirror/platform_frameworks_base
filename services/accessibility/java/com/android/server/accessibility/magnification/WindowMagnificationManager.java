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

package com.android.server.accessibility.magnification;

import android.annotation.Nullable;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.accessibility.IWindowMagnificationConnection;
import android.view.accessibility.IWindowMagnificationConnectionCallback;

import com.android.internal.annotations.VisibleForTesting;

/**
 * A class to manipulate  window magnification through {@link WindowMagnificationConnectionWrapper}.
 */
public final class WindowMagnificationManager {

    private static final String TAG = "WindowMagnificationMgr";
    private final Object mLock = new Object();
    @VisibleForTesting
    @Nullable WindowMagnificationConnectionWrapper mConnectionWrapper;
    private ConnectionCallback mConnectionCallback;

    /**
     * Sets {@link IWindowMagnificationConnection}.
     * @param connection {@link IWindowMagnificationConnection}
     */
    public void setConnection(@Nullable IWindowMagnificationConnection connection) {
        synchronized (mLock) {
            //Reset connectionWrapper.
            if (mConnectionWrapper != null) {
                mConnectionWrapper.setConnectionCallback(null);
                if (mConnectionCallback != null) {
                    mConnectionCallback.mExpiredDeathRecipient = true;
                }
                mConnectionWrapper.unlinkToDeath(mConnectionCallback);
                mConnectionWrapper = null;
            }
            if (connection != null) {
                mConnectionWrapper = new WindowMagnificationConnectionWrapper(connection);
            }

            if (mConnectionWrapper != null) {
                try {
                    mConnectionCallback = new ConnectionCallback();
                    mConnectionWrapper.linkToDeath(mConnectionCallback);
                    mConnectionWrapper.setConnectionCallback(mConnectionCallback);
                } catch (RemoteException e) {
                    Slog.e(TAG, "setConnection failed", e);
                    mConnectionWrapper = null;
                }
            }
        }
    }

    private class ConnectionCallback extends IWindowMagnificationConnectionCallback.Stub implements
            IBinder.DeathRecipient {
        private boolean mExpiredDeathRecipient = false;

        @Override
        public void onWindowMagnifierBoundsChanged(int display, Rect frame) throws RemoteException {
        }

        @Override
        public void onChangeMagnificationMode(int display, int magnificationMode)
                throws RemoteException {
        }

        @Override
        public void binderDied() {
            synchronized (mLock) {
                if (mExpiredDeathRecipient) {
                    Slog.w(TAG, "binderDied DeathRecipient is expired");
                    return;
                }
                mConnectionWrapper.unlinkToDeath(this);
                mConnectionWrapper = null;
                mConnectionCallback = null;
            }
        }
    }
}
