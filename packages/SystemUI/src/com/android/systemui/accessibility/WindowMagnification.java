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

import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_MAGNIFICATION_OVERLAP;

import android.annotation.MainThread;
import android.annotation.Nullable;
import android.content.Context;
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
import com.android.systemui.model.SysUiState;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.statusbar.CommandQueue;

import java.io.FileDescriptor;
import java.io.PrintWriter;

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

    private final ModeSwitchesController mModeSwitchesController;
    private final Handler mHandler;
    private final AccessibilityManager mAccessibilityManager;
    private final CommandQueue mCommandQueue;
    private final OverviewProxyService mOverviewProxyService;

    private WindowMagnificationConnectionImpl mWindowMagnificationConnectionImpl;
    private Configuration mLastConfiguration;
    private SysUiState mSysUiState;

    private static class AnimationControllerSupplier extends
            DisplayIdIndexSupplier<WindowMagnificationAnimationController> {

        private final Context mContext;
        private final Handler mHandler;
        private final WindowMagnifierCallback mWindowMagnifierCallback;
        private final SysUiState mSysUiState;

        AnimationControllerSupplier(Context context, Handler handler,
                WindowMagnifierCallback windowMagnifierCallback,
                DisplayManager displayManager, SysUiState sysUiState) {
            super(displayManager);
            mContext = context;
            mHandler = handler;
            mWindowMagnifierCallback = windowMagnifierCallback;
            mSysUiState = sysUiState;
        }

        @Override
        protected WindowMagnificationAnimationController createInstance(Display display) {
            final Context windowContext = mContext.createWindowContext(display,
                    TYPE_ACCESSIBILITY_MAGNIFICATION_OVERLAY, /* options */ null);
            final WindowMagnificationController controller = new WindowMagnificationController(
                    mContext,
                    mHandler, new SfVsyncFrameCallbackProvider(), null,
                    new SurfaceControl.Transaction(), mWindowMagnifierCallback, mSysUiState);
            return new WindowMagnificationAnimationController(windowContext, controller);
        }
    }

    @VisibleForTesting
    DisplayIdIndexSupplier<WindowMagnificationAnimationController> mAnimationControllerSupplier;

    @Inject
    public WindowMagnification(Context context, @Main Handler mainHandler,
            CommandQueue commandQueue, ModeSwitchesController modeSwitchesController,
            SysUiState sysUiState, OverviewProxyService overviewProxyService) {
        super(context);
        mHandler = mainHandler;
        mLastConfiguration = new Configuration(context.getResources().getConfiguration());
        mAccessibilityManager = mContext.getSystemService(AccessibilityManager.class);
        mCommandQueue = commandQueue;
        mModeSwitchesController = modeSwitchesController;
        mSysUiState = sysUiState;
        mOverviewProxyService = overviewProxyService;
        mAnimationControllerSupplier = new AnimationControllerSupplier(context,
                mHandler, this, context.getSystemService(DisplayManager.class), sysUiState);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        final int configDiff = newConfig.diff(mLastConfiguration);
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
        mOverviewProxyService.addCallback(new OverviewProxyService.OverviewProxyListener() {
            @Override
            public void onConnectionChanged(boolean isConnected) {
                if (isConnected) {
                    updateSysUiStateFlag();
                }
            }
        });
    }

    private void updateSysUiStateFlag() {
        //TODO(b/187510533): support multi-display once SysuiState supports it.
        final WindowMagnificationAnimationController controller =
                mAnimationControllerSupplier.valueAt(Display.DEFAULT_DISPLAY);
        if (controller != null) {
            controller.updateSysUiStateFlag();
        } else {
            // The instance is initialized when there is an IPC request. Considering
            // self-crash cases, we need to reset the flag in such situation.
            mSysUiState.setFlag(SYSUI_STATE_MAGNIFICATION_OVERLAP, false)
                    .commitUpdate(Display.DEFAULT_DISPLAY);
        }
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
    public void onAccessibilityActionPerformed(int displayId) {
        if (mWindowMagnificationConnectionImpl != null) {
            mWindowMagnificationConnectionImpl.onAccessibilityActionPerformed(displayId);
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

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println(TAG);
        mAnimationControllerSupplier.forEach(
                animationController -> animationController.dump(pw));
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
