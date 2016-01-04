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
 * limitations under the License
 */
package com.android.systemui.qs.external;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Handler;
import android.os.UserHandle;
import android.service.quicksettings.IQSTileService;
import android.support.annotation.VisibleForTesting;
import android.util.Log;

/**
 * Manages the priority which lets {@link TileServices} make decisions about which tiles
 * to bind.  Also holds on to and manages the {@link TileLifecycleManager}, informing it
 * of when it is allowed to bind based on decisions frome the {@link TileServices}.
 */
public class TileServiceManager {

    private static final long MIN_BIND_TIME = 5000;
    private static final long UNBIND_DELAY = 30000;

    public static final boolean DEBUG = true;

    private static final String TAG = "TileServiceManager";

    private final TileServices mServices;
    private final TileLifecycleManager mStateManager;
    private final Handler mHandler;
    private boolean mBindRequested;
    private boolean mBindAllowed;
    private boolean mBound;
    private int mPriority;
    private boolean mJustBound;
    private long mLastUpdate;

    TileServiceManager(TileServices tileServices, Handler handler, ComponentName component) {
        this(tileServices, handler, new TileLifecycleManager(handler,
                tileServices.getContext(), new Intent().setComponent(component),
                new UserHandle(ActivityManager.getCurrentUser())));
    }

    @VisibleForTesting
    TileServiceManager(TileServices tileServices, Handler handler,
            TileLifecycleManager tileLifecycleManager) {
        mServices = tileServices;
        mHandler = handler;
        mStateManager = tileLifecycleManager;
    }

    public IQSTileService getTileService() {
        return mStateManager;
    }

    public void setBindRequested(boolean bindRequested) {
        if (mBindRequested == bindRequested) return;
        mBindRequested = bindRequested;
        if (mBindAllowed && mBindRequested && !mBound) {
            bindService();
        } else {
            mServices.recalculateBindAllowance();
        }
        if (mBound && !mBindRequested) {
            // TODO: Schedule unbind.
        }
    }

    public void setLastUpdate(long lastUpdate) {
        mLastUpdate = lastUpdate;
        mServices.recalculateBindAllowance();
    }

    public void handleDestroy() {
        mStateManager.handleDestroy();
    }

    public void setBindAllowed(boolean allowed) {
        if (mBindAllowed == allowed) return;
        mBindAllowed = allowed;
        if (!mBindAllowed && mBound) {
            unbindService();
        } else if (mBindAllowed && mBindRequested && !mBound) {
            bindService();
        }
    }

    private void bindService() {
        if (mBound) {
            Log.e(TAG, "Service already bound");
            return;
        }
        mBound = true;
        mJustBound = true;
        mHandler.postDelayed(mJustBoundOver, MIN_BIND_TIME);
        mStateManager.setBindService(true);
    }

    private void unbindService() {
        if (!mBound) {
            Log.e(TAG, "Service not bound");
            return;
        }
        mBound = false;
        mJustBound = false;
        mStateManager.setBindService(false);
    }

    public void calculateBindPriority(long currentTime) {
        if (mStateManager.hasPendingClick()) {
            // Pending click is the most important thing, need to put this service at the top of
            // the list to be bound.
            mPriority = Integer.MAX_VALUE;
        } else if (mJustBound) {
            // If we just bound, lets not thrash on binding/unbinding too much, this is second most
            // important.
            mPriority = Integer.MAX_VALUE - 1;
        } else if (!mBindRequested) {
            // Don't care about binding right now, put us last.
            mPriority = Integer.MIN_VALUE;
        } else {
            // Order based on whether this was just updated.
            long timeSinceUpdate = currentTime - mLastUpdate;
            // Fit compare into integer space for simplicity. Make sure to leave MAX_VALUE and
            // MAX_VALUE - 1 for the more important states above.
            if (timeSinceUpdate > Integer.MAX_VALUE - 2) {
                mPriority = Integer.MAX_VALUE - 2;
            } else {
                mPriority = (int) timeSinceUpdate;
            }
        }
    }

    public int getBindPriority() {
        return mPriority;
    }

    @VisibleForTesting
    final Runnable mJustBoundOver = new Runnable() {
        @Override
        public void run() {
            mJustBound = false;
            mServices.recalculateBindAllowance();
        }
    };
}
