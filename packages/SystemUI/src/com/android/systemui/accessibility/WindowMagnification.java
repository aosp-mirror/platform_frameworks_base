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

package com.android.systemui.accessibility;

import static android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY;

import android.annotation.MainThread;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.IRemoteMagnificationAnimationCallback;
import android.view.accessibility.IWindowMagnificationConnection;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.graphics.SfVsyncFrameCallbackProvider;
import com.android.systemui.SystemUI;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.statusbar.CommandQueue;

import javax.inject.Inject;

/**
 * Class to handle the interaction with
 * {@link com.android.server.accessibility.AccessibilityManagerService}. It invokes
 * {@link AccessibilityManager#setWindowMagnificationConnection(IWindowMagnificationConnection)}
 * when {@code IStatusBar#requestWindowMagnificationConnection(boolean)} is called.
 */
@SysUISingleton
public class WindowMagnification extends SystemUI implements WindowMagnifierCallback,
        CommandQueue.Callbacks {
    private static final String TAG = "WindowMagnification";
    private static final int CONFIG_MASK =
            ActivityInfo.CONFIG_DENSITY | ActivityInfo.CONFIG_ORIENTATION
                    | ActivityInfo.CONFIG_LOCALE;

    private final ModeSwitchesController mModeSwitchesController;
    private final Handler mHandler;
    private final AccessibilityManager mAccessibilityManager;
    private final CommandQueue mCommandQueue;

    private WindowMagnificationConnectionImpl mWindowMagnificationConnectionImpl;
    private Configuration mLastConfiguration;

    private static class AnimationControllerSupplier extends
            DisplayIdIndexSupplier<WindowMagnificationAnimationController> {

        private final Context mContext;
        private final Handler mHandler;
        private final NavigationModeController mNavigationModeController;
        private final WindowMagnifierCallback mWindowMagnifierCallback;

        AnimationControllerSupplier(Context context, Handler handler,
                NavigationModeController navigationModeController,
                WindowMagnifierCallback windowMagnifierCallback, DisplayManager displayManager) {
            super(displayManager);
            mContext = context;
            mHandler = handler;
            mNavigationModeController = navigationModeController;
            mWindowMagnifierCallback = windowMagnifierCallback;
        }

        @Override
        protected WindowMagnificationAnimationController createInstance(Display display) {
            final Context windowContext = mContext.createWindowContext(display,
                    TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY, /* options */ null);
            final WindowMagnificationController controller = new WindowMagnificationController(
                    mContext,
                    mHandler, new SfVsyncFrameCallbackProvider(), null,
                    new SurfaceControl.Transaction(), mWindowMagnifierCallback);
            final int navBarMode = mNavigationModeController.addListener(
                    controller::onNavigationModeChanged);
            controller.onNavigationModeChanged(navBarMode);
            return new WindowMagnificationAnimationController(windowContext, controller);
        }
    }

    @VisibleForTesting
    DisplayIdIndexSupplier<WindowMagnificationAnimationController> mAnimationControllerSupplier;

    @Inject
    public WindowMagnification(Context context, @Main Handler mainHandler,
            CommandQueue commandQueue, ModeSwitchesController modeSwitchesController,
            NavigationModeController navigationModeController) {
        super(context);
        mHandler = mainHandler;
        mLastConfiguration = new Configuration(context.getResources().getConfiguration());
        mAccessibilityManager = mContext.getSystemService(AccessibilityManager.class);
        mCommandQueue = commandQueue;
        mModeSwitchesController = modeSwitchesController;
        mAnimationControllerSupplier = new AnimationControllerSupplier(context,
                mHandler, navigationModeController, this,
                context.getSystemService(DisplayManager.class));
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        final int configDiff = newConfig.diff(mLastConfiguration);
        if ((configDiff & CONFIG_MASK) == 0) {
            return;
        }
        mLastConfiguration.setTo(newConfig);
        mAnimationControllerSupplier.forEach(
                animationController -> animationController.onConfigurationChanged(configDiff));
        if (mModeSwitchesController != null) {
            mModeSwitchesController.onConfigurationChanged(configDiff);
        }
    }

    @Override
    public void start() {
        mCommandQueue.addCallback(this);
    }

    @MainThread
    void enableWindowMagnification(int displayId, float scale, float centerX, float centerY,
            @Nullable IRemoteMagnificationAnimationCallback callback) {
        final WindowMagnificationAnimationController windowMagnificationAnimationController =
                mAnimationControllerSupplier.get(displayId);
        if (windowMagnificationAnimationController != null) {
            windowMagnificationAnimationController.enableWindowMagnification(scale, centerX,
                    centerY, callback);
        }
    }

    @MainThread
    void setScale(int displayId, float scale) {
        final WindowMagnificationAnimationController windowMagnificationAnimationController =
                mAnimationControllerSupplier.get(displayId);
        if (windowMagnificationAnimationController != null) {
            windowMagnificationAnimationController.setScale(scale);
        }
    }

    @MainThread
    void moveWindowMagnifier(int displayId, float offsetX, float offsetY) {
        final WindowMagnificationAnimationController windowMagnificationAnimationController =
                mAnimationControllerSupplier.get(displayId);
        if (windowMagnificationAnimationController != null) {
            windowMagnificationAnimationController.moveWindowMagnifier(offsetX, offsetY);
        }
    }

    @MainThread
    void disableWindowMagnification(int displayId,
            @Nullable IRemoteMagnificationAnimationCallback callback) {
        final WindowMagnificationAnimationController windowMagnificationAnimationController =
                mAnimationControllerSupplier.get(displayId);
        if (windowMagnificationAnimationController != null) {
            windowMagnificationAnimationController.deleteWindowMagnification(callback);
        }
    }

    @Override
    public void onWindowMagnifierBoundsChanged(int displayId, Rect frame) {
        if (mWindowMagnificationConnectionImpl != null) {
            mWindowMagnificationConnectionImpl.onWindowMagnifierBoundsChanged(displayId, frame);
        }
    }

    @Override
    public void onSourceBoundsChanged(int displayId, Rect sourceBounds) {
        if (mWindowMagnificationConnectionImpl != null) {
            mWindowMagnificationConnectionImpl.onSourceBoundsChanged(displayId, sourceBounds);
        }
    }

    @Override
    public void onPerformScaleAction(int displayId, float scale) {
        if (mWindowMagnificationConnectionImpl != null) {
            mWindowMagnificationConnectionImpl.onPerformScaleAction(displayId, scale);
        }
    }

    @Override
    public void requestWindowMagnificationConnection(boolean connect) {
        if (connect) {
            setWindowMagnificationConnection();
        } else {
            clearWindowMagnificationConnection();
        }
    }

    private void setWindowMagnificationConnection() {
        if (mWindowMagnificationConnectionImpl == null) {
            mWindowMagnificationConnectionImpl = new WindowMagnificationConnectionImpl(this,
                    mHandler, mModeSwitchesController);
        }
        mAccessibilityManager.setWindowMagnificationConnection(
                mWindowMagnificationConnectionImpl);
    }

    private void clearWindowMagnificationConnection() {
        mAccessibilityManager.setWindowMagnificationConnection(null);
        //TODO: destroy controllers.
    }
}
