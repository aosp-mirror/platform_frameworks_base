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

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_DOCK_DIVIDER;
import static android.view.WindowManager.SHELL_ROOT_LAYER_DIVIDER;
import static android.view.WindowManager.SHELL_ROOT_LAYER_PIP;

import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_WINDOW_ANIMATION;
import static com.android.server.wm.WindowManagerService.MAX_ANIMATION_DURATION;

import android.annotation.NonNull;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.DisplayInfo;
import android.view.IWindow;
import android.view.SurfaceControl;
import android.view.WindowInfo;
import android.view.WindowManager;
import android.view.animation.Animation;

/**
 * Represents a piece of the hierarchy under which a client Shell can manage sub-windows.
 */
public class ShellRoot {
    private static final String TAG = "ShellRoot";
    private final DisplayContent mDisplayContent;
    private final int mShellRootLayer;
    private IWindow mClient;
    private WindowToken mToken;
    private final IBinder.DeathRecipient mDeathRecipient;
    private SurfaceControl mSurfaceControl = null;
    private IWindow mAccessibilityWindow;
    private IBinder.DeathRecipient mAccessibilityWindowDeath;
    private int mWindowType;

    ShellRoot(@NonNull IWindow client, @NonNull DisplayContent dc,
            @WindowManager.ShellRootLayer final int shellRootLayer) {
        mDisplayContent = dc;
        mShellRootLayer = shellRootLayer;
        mDeathRecipient = () -> mDisplayContent.removeShellRoot(shellRootLayer);
        try {
            client.asBinder().linkToDeath(mDeathRecipient, 0);
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to add shell root layer " + shellRootLayer + " on display "
                    + dc.getDisplayId(), e);
            return;
        }
        mClient = client;
        switch (shellRootLayer) {
            case SHELL_ROOT_LAYER_DIVIDER:
                mWindowType = TYPE_DOCK_DIVIDER;
                break;
            case SHELL_ROOT_LAYER_PIP:
                mWindowType = TYPE_APPLICATION_OVERLAY;
                break;
            default:
                throw new IllegalArgumentException(shellRootLayer
                        + " is not an acceptable shell root layer.");
        }
        mToken = new WindowToken.Builder(dc.mWmService, client.asBinder(), mWindowType)
                .setDisplayContent(dc)
                .setPersistOnEmpty(true)
                .setOwnerCanManageAppTokens(true)
                .build();
        mSurfaceControl = mToken.makeChildSurface(null)
                .setContainerLayer()
                .setName("Shell Root Leash " + dc.getDisplayId())
                .setCallsite("ShellRoot")
                .build();
        mToken.getPendingTransaction().show(mSurfaceControl);
    }

    int getWindowType() {
        return mWindowType;
    }

    void clear() {
        if (mClient != null) {
            mClient.asBinder().unlinkToDeath(mDeathRecipient, 0);
            mClient = null;
        }
        if (mToken != null) {
            mToken.removeImmediately();
            mToken = null;
        }
    }

    SurfaceControl getSurfaceControl() {
        return mSurfaceControl;
    }

    IWindow getClient() {
        return mClient;
    }

    void startAnimation(Animation anim) {
        // Only do this for the divider
        if (mToken.windowType != TYPE_DOCK_DIVIDER) {
            return;
        }

        DisplayInfo displayInfo = mToken.getFixedRotationTransformDisplayInfo();
        if (displayInfo == null) {
            displayInfo = mDisplayContent.getDisplayInfo();
        }

        // Mostly copied from WindowState to enable keyguard transition animation
        anim.initialize(displayInfo.logicalWidth, displayInfo.logicalHeight,
                displayInfo.appWidth, displayInfo.appHeight);
        anim.restrictDuration(MAX_ANIMATION_DURATION);
        anim.scaleCurrentDuration(mDisplayContent.mWmService.getWindowAnimationScaleLocked());
        final AnimationAdapter adapter = new LocalAnimationAdapter(
                new WindowAnimationSpec(anim, new Point(0, 0), false /* canSkipFirstFrame */,
                        0 /* windowCornerRadius */),
                mDisplayContent.mWmService.mSurfaceAnimationRunner);
        mToken.startAnimation(mToken.getPendingTransaction(), adapter, false /* hidden */,
                ANIMATION_TYPE_WINDOW_ANIMATION);
    }

    WindowInfo getWindowInfo() {
        if (mShellRootLayer != SHELL_ROOT_LAYER_DIVIDER
                && mShellRootLayer != SHELL_ROOT_LAYER_PIP) {
            return null;
        }
        if (mShellRootLayer == SHELL_ROOT_LAYER_DIVIDER
                && !mDisplayContent.getDefaultTaskDisplayArea().isSplitScreenModeActivated()) {
            return null;
        }
        if (mShellRootLayer == SHELL_ROOT_LAYER_PIP
                && mDisplayContent.getDefaultTaskDisplayArea().getRootPinnedTask() == null) {
            return null;
        }
        if (mAccessibilityWindow == null) {
            return null;
        }
        WindowInfo windowInfo = WindowInfo.obtain();
        windowInfo.displayId = mToken.getDisplayArea().getDisplayContent().mDisplayId;
        windowInfo.type = mToken.windowType;
        windowInfo.layer = mToken.getWindowLayerFromType();
        windowInfo.token = mAccessibilityWindow.asBinder();
        windowInfo.focused = false;
        windowInfo.hasFlagWatchOutsideTouch = false;
        final Rect regionRect = new Rect();


        // DividerView
        if (mShellRootLayer == SHELL_ROOT_LAYER_DIVIDER) {
            windowInfo.inPictureInPicture = false;
            mDisplayContent.getDockedDividerController().getTouchRegion(regionRect);
            windowInfo.regionInScreen.set(regionRect);
            windowInfo.title = "Splitscreen Divider";
        }
        // PipMenuView
        if (mShellRootLayer == SHELL_ROOT_LAYER_PIP) {
            windowInfo.inPictureInPicture = true;
            mDisplayContent.getDefaultTaskDisplayArea().getRootPinnedTask().getBounds(regionRect);
            windowInfo.regionInScreen.set(regionRect);
            windowInfo.title = "Picture-in-Picture menu";
        }
        return windowInfo;
    }

    void setAccessibilityWindow(IWindow window) {
        if (mAccessibilityWindow != null) {
            mAccessibilityWindow.asBinder().unlinkToDeath(mAccessibilityWindowDeath, 0);
        }
        mAccessibilityWindow = window;
        if (mAccessibilityWindow != null) {
            try {
                mAccessibilityWindowDeath = () -> {
                    synchronized (mDisplayContent.mWmService.mGlobalLock) {
                        mAccessibilityWindow = null;
                        setAccessibilityWindow(null);
                    }
                };
                mAccessibilityWindow.asBinder().linkToDeath(mAccessibilityWindowDeath, 0);
            } catch (RemoteException e) {
                mAccessibilityWindow = null;
            }
        }
        if (mDisplayContent.mWmService.mAccessibilityController.hasCallbacks()) {
            mDisplayContent.mWmService.mAccessibilityController.onSomeWindowResizedOrMoved(
                    mDisplayContent.getDisplayId());
        }
    }
}
