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

package com.android.server.policy;

import android.annotation.Nullable;
import android.content.Context;
import android.graphics.Rect;
import android.hardware.ICameraService;
import android.hardware.devicestate.DeviceStateManager;
import android.hardware.devicestate.DeviceStateManager.FoldStateListener;
import android.hardware.display.DisplayManagerInternal;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.IDisplayFoldListener;

import com.android.server.DisplayThread;
import com.android.server.LocalServices;
import com.android.server.camera.CameraServiceProxy;
import com.android.server.wm.WindowManagerInternal;

/**
 * Controls the behavior of foldable devices whose screen can literally bend and fold.
 * TODO(b/126160895): Move DisplayFoldController from PhoneWindowManager to DisplayPolicy.
 */
class DisplayFoldController {
    private static final String TAG = "DisplayFoldController";

    private final WindowManagerInternal mWindowManagerInternal;
    private final DisplayManagerInternal mDisplayManagerInternal;
    // Camera service proxy can be disabled through a config.
    @Nullable
    private final CameraServiceProxy mCameraServiceProxy;
    private final int mDisplayId;
    private final Handler mHandler;

    /** The display area while device is folded. */
    private final Rect mFoldedArea;
    /** The display area to override the original folded area. */
    private Rect mOverrideFoldedArea = new Rect();

    private final DisplayInfo mNonOverrideDisplayInfo = new DisplayInfo();
    private final RemoteCallbackList<IDisplayFoldListener> mListeners = new RemoteCallbackList<>();
    private Boolean mFolded;
    private String mFocusedApp;
    private final DisplayFoldDurationLogger mDurationLogger = new DisplayFoldDurationLogger();

    DisplayFoldController(
            Context context, WindowManagerInternal windowManagerInternal,
            DisplayManagerInternal displayManagerInternal,
            @Nullable CameraServiceProxy cameraServiceProxy, int displayId, Rect foldedArea,
            Handler handler) {
        mWindowManagerInternal = windowManagerInternal;
        mDisplayManagerInternal = displayManagerInternal;
        mCameraServiceProxy = cameraServiceProxy;
        mDisplayId = displayId;
        mFoldedArea = new Rect(foldedArea);
        mHandler = handler;

        DeviceStateManager deviceStateManager = context.getSystemService(DeviceStateManager.class);
        deviceStateManager.registerCallback(new HandlerExecutor(handler),
                new FoldStateListener(context, folded -> setDeviceFolded(folded)));
    }

    void finishedGoingToSleep() {
        mDurationLogger.onFinishedGoingToSleep();
    }

    void finishedWakingUp() {
        mDurationLogger.onFinishedWakingUp(mFolded);
    }

    private void setDeviceFolded(boolean folded) {
        if (mFolded != null && mFolded == folded) {
            return;
        }

        final Rect foldedArea;
        if (!mOverrideFoldedArea.isEmpty()) {
            foldedArea = mOverrideFoldedArea;
        } else if (!mFoldedArea.isEmpty()) {
            foldedArea = mFoldedArea;
        } else {
            foldedArea = null;
        }

        // Only do display scaling/cropping if it has been configured to do so
        if (foldedArea != null) {
            if (folded) {

                mDisplayManagerInternal.getNonOverrideDisplayInfo(
                        mDisplayId, mNonOverrideDisplayInfo);
                final int dx = (mNonOverrideDisplayInfo.logicalWidth - foldedArea.width()) / 2
                        - foldedArea.left;
                final int dy = (mNonOverrideDisplayInfo.logicalHeight - foldedArea.height()) / 2
                        - foldedArea.top;

                // Bypass scaling otherwise LogicalDisplay will scale contents by default.
                mDisplayManagerInternal.setDisplayScalingDisabled(mDisplayId, true);
                mWindowManagerInternal.setForcedDisplaySize(mDisplayId,
                        foldedArea.width(), foldedArea.height());
                mDisplayManagerInternal.setDisplayOffsets(mDisplayId, -dx, -dy);
            } else {
                mDisplayManagerInternal.setDisplayScalingDisabled(mDisplayId, false);
                mWindowManagerInternal.clearForcedDisplaySize(mDisplayId);
                mDisplayManagerInternal.setDisplayOffsets(mDisplayId, 0, 0);
            }
        }

        if (mCameraServiceProxy != null) {
            if (folded) {
                mCameraServiceProxy.setDeviceStateFlags(ICameraService.DEVICE_STATE_FOLDED);
            } else {
                mCameraServiceProxy.clearDeviceStateFlags(ICameraService.DEVICE_STATE_FOLDED);
            }
        } else {
            Slog.w(TAG, "Camera service unavailable to toggle folded state.");
        }

        mDurationLogger.setDeviceFolded(folded);
        mDurationLogger.logFocusedAppWithFoldState(folded, mFocusedApp);
        mFolded = folded;

        final int n = mListeners.beginBroadcast();
        for (int i = 0; i < n; i++) {
            try {
                mListeners.getBroadcastItem(i).onDisplayFoldChanged(mDisplayId, folded);
            } catch (RemoteException e) {
                // Listener died.
            }
        }
        mListeners.finishBroadcast();
    }

    void registerDisplayFoldListener(IDisplayFoldListener listener) {
        mListeners.register(listener);
        if (mFolded == null) {
            return;
        }
        mHandler.post(() -> {
            try {
                listener.onDisplayFoldChanged(mDisplayId, mFolded);
            } catch (RemoteException e) {
                // Listener died.
            }
        });
    }

    void unregisterDisplayFoldListener(IDisplayFoldListener listener) {
        mListeners.unregister(listener);
    }

    void setOverrideFoldedArea(Rect area) {
        mOverrideFoldedArea.set(area);
    }

    Rect getFoldedArea() {
        if (!mOverrideFoldedArea.isEmpty()) {
            return mOverrideFoldedArea;
        } else {
            return mFoldedArea;
        }
    }

    void onDefaultDisplayFocusChanged(String pkg) {
        mFocusedApp = pkg;
    }

    static DisplayFoldController create(Context context, int displayId) {
        final WindowManagerInternal windowManagerService =
                LocalServices.getService(WindowManagerInternal.class);
        final DisplayManagerInternal displayService =
                LocalServices.getService(DisplayManagerInternal.class);
        final CameraServiceProxy cameraServiceProxy =
                LocalServices.getService(CameraServiceProxy.class);

        final String configFoldedArea = context.getResources().getString(
                com.android.internal.R.string.config_foldedArea);
        final Rect foldedArea;
        if (configFoldedArea == null || configFoldedArea.isEmpty()) {
            foldedArea = new Rect();
        } else {
            foldedArea = Rect.unflattenFromString(configFoldedArea);
        }

        return new DisplayFoldController(context, windowManagerService, displayService,
                cameraServiceProxy, displayId, foldedArea, DisplayThread.getHandler());
    }
}
