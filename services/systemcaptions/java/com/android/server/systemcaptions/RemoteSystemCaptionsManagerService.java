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

package com.android.server.systemcaptions;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.UserHandle;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

/** Manages the connection to the remote system captions manager service. */
final class RemoteSystemCaptionsManagerService {

    private static final String TAG = RemoteSystemCaptionsManagerService.class.getSimpleName();

    private static final String SERVICE_INTERFACE =
            "android.service.systemcaptions.SystemCaptionsManagerService";

    private final Object mLock = new Object();

    private final Context mContext;
    private final Intent mIntent;
    private final ComponentName mComponentName;
    private final int mUserId;
    private final boolean mVerbose;
    private final Handler mHandler;

    private final RemoteServiceConnection mServiceConnection = new RemoteServiceConnection();

    @GuardedBy("mLock")
    @Nullable private IBinder mService;

    @GuardedBy("mLock")
    private boolean mBinding = false;

    @GuardedBy("mLock")
    private boolean mDestroyed = false;

    RemoteSystemCaptionsManagerService(
            Context context, ComponentName componentName, int userId, boolean verbose) {
        mContext = context;
        mComponentName = componentName;
        mUserId = userId;
        mVerbose = verbose;
        mIntent = new Intent(SERVICE_INTERFACE).setComponent(componentName);
        mHandler = new Handler(Looper.getMainLooper());
    }

    void initialize() {
        if (mVerbose) {
            Slog.v(TAG, "initialize()");
        }
        ensureBound();
    }

    void destroy() {
        if (mVerbose) {
            Slog.v(TAG, "destroy()");
        }

        synchronized (mLock) {
            if (mDestroyed) {
                if (mVerbose) {
                    Slog.v(TAG, "destroy(): Already destroyed");
                }
                return;
            }
            mDestroyed = true;
            ensureUnboundLocked();
        }
    }

    boolean isDestroyed() {
        synchronized (mLock) {
            return mDestroyed;
        }
    }

    private void ensureBound() {
        synchronized (mLock) {
            if (mService != null || mBinding) {
                return;
            }

            if (mVerbose) {
                Slog.v(TAG, "ensureBound(): binding");
            }
            mBinding = true;

            int flags = Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE
                    | Context.BIND_INCLUDE_CAPABILITIES;
            boolean willBind = mContext.bindServiceAsUser(mIntent, mServiceConnection, flags,
                    mHandler, new UserHandle(mUserId));
            if (!willBind) {
                Slog.w(TAG, "Could not bind to " + mIntent + " with flags " + flags);
                mBinding = false;
                mService = null;
            }
        }
    }

    @GuardedBy("mLock")
    private void ensureUnboundLocked() {
        if (mService == null && !mBinding) {
            return;
        }

        mBinding = false;
        mService = null;

        if (mVerbose) {
            Slog.v(TAG, "ensureUnbound(): unbinding");
        }
        mContext.unbindService(mServiceConnection);
    }

    private class RemoteServiceConnection implements ServiceConnection {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (mLock) {
                if (mVerbose) {
                    Slog.v(TAG, "onServiceConnected()");
                }
                if (mDestroyed || !mBinding) {
                    Slog.wtf(TAG, "onServiceConnected() dispatched after unbindService");
                    return;
                }
                mBinding = false;
                mService = service;
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (mLock) {
                if (mVerbose) {
                    Slog.v(TAG, "onServiceDisconnected()");
                }
                mBinding = true;
                mService = null;
            }
        }
    }
}
