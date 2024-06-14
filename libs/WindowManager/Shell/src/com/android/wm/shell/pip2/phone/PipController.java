/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.wm.shell.pip2.phone;

import static android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE;

import static com.android.wm.shell.common.ExecutorUtils.executeRemoteCallWithTaskPermission;
import static com.android.wm.shell.sysui.ShellSharedConstants.KEY_EXTRA_SHELL_PIP;

import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.view.InsetsState;
import android.view.SurfaceControl;

import androidx.annotation.BinderThread;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.ExternalInterfaceBinder;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.pip.IPip;
import com.android.wm.shell.common.pip.IPipAnimationListener;
import com.android.wm.shell.common.pip.PipBoundsAlgorithm;
import com.android.wm.shell.common.pip.PipBoundsState;
import com.android.wm.shell.common.pip.PipDisplayLayoutState;
import com.android.wm.shell.common.pip.PipUtils;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.sysui.ConfigurationChangeListener;
import com.android.wm.shell.sysui.ShellController;
import com.android.wm.shell.sysui.ShellInit;

/**
 * Manages the picture-in-picture (PIP) UI and states for Phones.
 */
public class PipController implements ConfigurationChangeListener,
        DisplayController.OnDisplaysChangedListener, RemoteCallable<PipController> {
    private static final String TAG = PipController.class.getSimpleName();

    private Context mContext;
    private ShellController mShellController;
    private DisplayController mDisplayController;
    private DisplayInsetsController mDisplayInsetsController;
    private PipBoundsState mPipBoundsState;
    private PipBoundsAlgorithm mPipBoundsAlgorithm;
    private PipDisplayLayoutState mPipDisplayLayoutState;
    private PipScheduler mPipScheduler;
    private ShellExecutor mMainExecutor;

    private PipController(Context context,
            ShellInit shellInit,
            ShellController shellController,
            DisplayController displayController,
            DisplayInsetsController displayInsetsController,
            PipBoundsState pipBoundsState,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            PipDisplayLayoutState pipDisplayLayoutState,
            PipScheduler pipScheduler,
            ShellExecutor mainExecutor) {
        mContext = context;
        mShellController = shellController;
        mDisplayController = displayController;
        mDisplayInsetsController = displayInsetsController;
        mPipBoundsState = pipBoundsState;
        mPipBoundsAlgorithm = pipBoundsAlgorithm;
        mPipDisplayLayoutState = pipDisplayLayoutState;
        mPipScheduler = pipScheduler;
        mMainExecutor = mainExecutor;

        if (PipUtils.isPip2ExperimentEnabled()) {
            shellInit.addInitCallback(this::onInit, this);
        }
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public ShellExecutor getRemoteCallExecutor() {
        return mMainExecutor;
    }

    private void onInit() {
        // Ensure that we have the display info in case we get calls to update the bounds before the
        // listener calls back
        mPipDisplayLayoutState.setDisplayId(mContext.getDisplayId());
        DisplayLayout layout = new DisplayLayout(mContext, mContext.getDisplay());
        mPipDisplayLayoutState.setDisplayLayout(layout);

        mShellController.addConfigurationChangeListener(this);
        mDisplayController.addDisplayWindowListener(this);
        mDisplayInsetsController.addInsetsChangedListener(mPipDisplayLayoutState.getDisplayId(),
                new DisplayInsetsController.OnInsetsChangedListener() {
                    @Override
                    public void insetsChanged(InsetsState insetsState) {
                        onDisplayChanged(mDisplayController
                                        .getDisplayLayout(mPipDisplayLayoutState.getDisplayId()));
                    }
                });

        // Allow other outside processes to bind to PiP controller using the key below.
        mShellController.addExternalInterface(KEY_EXTRA_SHELL_PIP,
                this::createExternalInterface, this);
    }

    /**
     * Instantiates {@link PipController}, returns {@code null} if the feature not supported.
     */
    public static PipController create(Context context,
            ShellInit shellInit,
            ShellController shellController,
            DisplayController displayController,
            DisplayInsetsController displayInsetsController,
            PipBoundsState pipBoundsState,
            PipBoundsAlgorithm pipBoundsAlgorithm,
            PipDisplayLayoutState pipDisplayLayoutState,
            PipScheduler pipScheduler,
            ShellExecutor mainExecutor) {
        if (!context.getPackageManager().hasSystemFeature(FEATURE_PICTURE_IN_PICTURE)) {
            ProtoLog.w(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Device doesn't support Pip feature", TAG);
            return null;
        }
        return new PipController(context, shellInit, shellController, displayController,
                displayInsetsController, pipBoundsState, pipBoundsAlgorithm, pipDisplayLayoutState,
                pipScheduler, mainExecutor);
    }

    private ExternalInterfaceBinder createExternalInterface() {
        return new IPipImpl(this);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfiguration) {
        mPipDisplayLayoutState.onConfigurationChanged();
    }

    @Override
    public void onThemeChanged() {
        onDisplayChanged(new DisplayLayout(mContext, mContext.getDisplay()));
    }

    @Override
    public void onDisplayAdded(int displayId) {
        if (displayId != mPipDisplayLayoutState.getDisplayId()) {
            return;
        }
        onDisplayChanged(mDisplayController.getDisplayLayout(displayId));
    }

    @Override
    public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
        if (displayId != mPipDisplayLayoutState.getDisplayId()) {
            return;
        }
        onDisplayChanged(mDisplayController.getDisplayLayout(displayId));
    }

    private void onDisplayChanged(DisplayLayout layout) {
        mPipDisplayLayoutState.setDisplayLayout(layout);
    }

    private Rect getSwipePipToHomeBounds(ComponentName componentName, ActivityInfo activityInfo,
            PictureInPictureParams pictureInPictureParams,
            int launcherRotation, Rect hotseatKeepClearArea) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "getSwipePipToHomeBounds: %s", componentName);
        mPipBoundsState.setBoundsStateForEntry(componentName, activityInfo, pictureInPictureParams,
                mPipBoundsAlgorithm);
        return mPipBoundsAlgorithm.getEntryDestinationBounds();
    }

    private void onSwipePipToHomeAnimationStart(int taskId, ComponentName componentName,
            Rect destinationBounds, SurfaceControl overlay, Rect appBounds,
            Rect sourceRectHint) {
        ProtoLog.d(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                "onSwipePipToHomeAnimationStart: %s", componentName);
        mPipScheduler.setInSwipePipToHomeTransition(true);
        // TODO: cache the overlay if provided for reparenting later.
    }

    /**
     * The interface for calls from outside the host process.
     */
    @BinderThread
    private static class IPipImpl extends IPip.Stub implements ExternalInterfaceBinder {
        private PipController mController;

        IPipImpl(PipController controller) {
            mController = controller;
        }

        /**
         * Invalidates this instance, preventing future calls from updating the controller.
         */
        @Override
        public void invalidate() {
            mController = null;
        }

        @Override
        public Rect startSwipePipToHome(ComponentName componentName, ActivityInfo activityInfo,
                PictureInPictureParams pictureInPictureParams, int launcherRotation,
                Rect keepClearArea) {
            Rect[] result = new Rect[1];
            executeRemoteCallWithTaskPermission(mController, "startSwipePipToHome",
                    (controller) -> {
                        result[0] = controller.getSwipePipToHomeBounds(componentName, activityInfo,
                                pictureInPictureParams, launcherRotation, keepClearArea);
                    }, true /* blocking */);
            return result[0];
        }

        @Override
        public void stopSwipePipToHome(int taskId, ComponentName componentName,
                Rect destinationBounds, SurfaceControl overlay, Rect appBounds,
                Rect sourceRectHint) {
            if (overlay != null) {
                overlay.setUnreleasedWarningCallSite("PipController.stopSwipePipToHome");
            }
            executeRemoteCallWithTaskPermission(mController, "stopSwipePipToHome",
                    (controller) -> controller.onSwipePipToHomeAnimationStart(
                            taskId, componentName, destinationBounds, overlay, appBounds,
                            sourceRectHint));
        }

        @Override
        public void abortSwipePipToHome(int taskId, ComponentName componentName) {}

        @Override
        public void setShelfHeight(boolean visible, int height) {}

        @Override
        public void setLauncherKeepClearAreaHeight(boolean visible, int height) {}

        @Override
        public void setLauncherAppIconSize(int iconSizePx) {}

        @Override
        public void setPipAnimationListener(IPipAnimationListener listener) {
            // TODO: set a proper animation listener to update the Launcher state as needed.
        }

        @Override
        public void setPipAnimationTypeToAlpha() {}
    }
}
