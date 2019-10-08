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

import static android.view.WindowManager.DOCKED_INVALID;

import android.app.ActivityTaskManager;
import android.graphics.Rect;
import android.os.RemoteException;
import android.util.Log;
import android.view.WindowManagerGlobal;

import com.android.internal.annotations.GuardedBy;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Proxy to simplify calls into window manager/activity manager
 */
public class WindowManagerProxy {

    private static final String TAG = "WindowManagerProxy";

    private static final WindowManagerProxy sInstance = new WindowManagerProxy();

    @GuardedBy("mDockedRect")
    private final Rect mDockedRect = new Rect();
    private final Rect mTempDockedTaskRect = new Rect();
    private final Rect mTempDockedInsetRect = new Rect();
    private final Rect mTempOtherTaskRect = new Rect();
    private final Rect mTempOtherInsetRect = new Rect();

    private final Rect mTmpRect1 = new Rect();
    private final Rect mTmpRect2 = new Rect();
    private final Rect mTmpRect3 = new Rect();
    private final Rect mTmpRect4 = new Rect();
    private final Rect mTmpRect5 = new Rect();

    @GuardedBy("mDockedRect")
    private final Rect mTouchableRegion = new Rect();

    private boolean mDimLayerVisible;
    private int mDimLayerTargetWindowingMode;
    private float mDimLayerAlpha;

    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();

    private final Runnable mResizeRunnable = new Runnable() {
        @Override
        public void run() {
            synchronized (mDockedRect) {
                mTmpRect1.set(mDockedRect);
                mTmpRect2.set(mTempDockedTaskRect);
                mTmpRect3.set(mTempDockedInsetRect);
                mTmpRect4.set(mTempOtherTaskRect);
                mTmpRect5.set(mTempOtherInsetRect);
            }
            try {
                ActivityTaskManager.getService()
                        .resizeDockedStack(mTmpRect1,
                                mTmpRect2.isEmpty() ? null : mTmpRect2,
                                mTmpRect3.isEmpty() ? null : mTmpRect3,
                                mTmpRect4.isEmpty() ? null : mTmpRect4,
                                mTmpRect5.isEmpty() ? null : mTmpRect5);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to resize stack: " + e);
            }
        }
    };

    private final Runnable mDismissRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                ActivityTaskManager.getService().dismissSplitScreenMode(false /* onTop */);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to remove stack: " + e);
            }
        }
    };

    private final Runnable mMaximizeRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                ActivityTaskManager.getService().dismissSplitScreenMode(true /* onTop */);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to resize stack: " + e);
            }
        }
    };

    private final Runnable mDimLayerRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                WindowManagerGlobal.getWindowManagerService().setResizeDimLayer(mDimLayerVisible,
                        mDimLayerTargetWindowingMode, mDimLayerAlpha);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to resize stack: " + e);
            }
        }
    };

    private final Runnable mSetTouchableRegionRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                synchronized (mDockedRect) {
                    mTmpRect1.set(mTouchableRegion);
                }
                WindowManagerGlobal.getWindowManagerService().setDockedStackDividerTouchRegion(
                        mTmpRect1);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to set touchable region: " + e);
            }
        }
    };

    private WindowManagerProxy() {
    }

    public static WindowManagerProxy getInstance() {
        return sInstance;
    }

    public void resizeDockedStack(Rect docked, Rect tempDockedTaskRect, Rect tempDockedInsetRect,
            Rect tempOtherTaskRect, Rect tempOtherInsetRect) {
        synchronized (mDockedRect) {
            mDockedRect.set(docked);
            if (tempDockedTaskRect != null) {
                mTempDockedTaskRect.set(tempDockedTaskRect);
            } else {
                mTempDockedTaskRect.setEmpty();
            }
            if (tempDockedInsetRect != null) {
                mTempDockedInsetRect.set(tempDockedInsetRect);
            } else {
                mTempDockedInsetRect.setEmpty();
            }
            if (tempOtherTaskRect != null) {
                mTempOtherTaskRect.set(tempOtherTaskRect);
            } else {
                mTempOtherTaskRect.setEmpty();
            }
            if (tempOtherInsetRect != null) {
                mTempOtherInsetRect.set(tempOtherInsetRect);
            } else {
                mTempOtherInsetRect.setEmpty();
            }
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
                    ActivityTaskManager.getService().setSplitScreenResizing(resizing);
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

    public void setResizeDimLayer(boolean visible, int targetWindowingMode, float alpha) {
        mDimLayerVisible = visible;
        mDimLayerTargetWindowingMode = targetWindowingMode;
        mDimLayerAlpha = alpha;
        mExecutor.execute(mDimLayerRunnable);
    }

    public void setTouchRegion(Rect region) {
        synchronized (mDockedRect) {
            mTouchableRegion.set(region);
        }
        mExecutor.execute(mSetTouchableRegionRunnable);
    }
}
