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

package com.android.systemui.stackdivider;

import android.app.ActivityManagerNative;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.Log;
import android.view.WindowManagerGlobal;

import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static android.app.ActivityManager.StackId.DOCKED_STACK_ID;
import static android.view.WindowManager.DOCKED_INVALID;

/**
 * Proxy to simplify calls into window manager/activity manager
 */
public class WindowManagerProxy {

    private static final String TAG = "WindowManagerProxy";

    private static final WindowManagerProxy sInstance = new WindowManagerProxy();

    @GuardedBy("mResizeRect")
    private final Rect mResizeRect = new Rect();
    private final Rect mTmpRect = new Rect();
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private final Runnable mResizeRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mResizeRect) {
                mTmpRect.set(mResizeRect);
            }
            try {
                ActivityManagerNative.getDefault().resizeStack(DOCKED_STACK_ID, mTmpRect, true);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to resize stack: " + e);
            }
        }
    };

    private final Runnable mDismissRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                ActivityManagerNative.getDefault().moveTasksToFullscreenStack(DOCKED_STACK_ID);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to remove stack: " + e);
            }
        }
    };

    private final Runnable mMaximizeRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                ActivityManagerNative.getDefault().resizeStack(DOCKED_STACK_ID, null, true);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to resize stack: " + e);
            }
        }
    };

    private WindowManagerProxy() {
    }

    public static WindowManagerProxy getInstance() {
        return sInstance;
    }

    public void resizeDockedStack(Rect rect) {
        synchronized (mResizeRect) {
            mResizeRect.set(rect);
        }
        mExecutor.execute(mResizeRunnable);
    }

    public void dismissDockedStack() {
        mExecutor.execute(mDismissRunnable);
    }

    public void maximizeDockedStack() {
        mExecutor.execute(mMaximizeRunnable);
    }

    public void setResizing(final boolean resizing) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    WindowManagerGlobal.getWindowManagerService().setDockedStackResizing(resizing);
                } catch (RemoteException e) {
                    Log.w(TAG, "Error calling setDockedStackResizing: " + e);
                }
            }
        });
    }

    public int getDockSide() {
        try {
            return WindowManagerGlobal.getWindowManagerService().getDockedStackSide();
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to get dock side: " + e);
        }
        return DOCKED_INVALID;
    }
}
