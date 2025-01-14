/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.wm.shell.appzoomout;

import static android.app.WindowConfiguration.ROTATION_UNDEFINED;
import static android.view.Display.DEFAULT_DISPLAY;
import static com.android.systemui.Flags.spatialModelAppPushback;

import android.app.ActivityManager;
import android.app.WindowConfiguration;
import android.content.Context;
import android.content.res.Configuration;
import android.util.Slog;
import android.window.DisplayAreaInfo;
import android.window.WindowContainerTransaction;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayChangeController;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.shared.annotations.ExternalThread;
import com.android.wm.shell.shared.annotations.ShellMainThread;
import com.android.wm.shell.sysui.ShellInit;

/** Class that manages the app zoom out UI and states. */
public class AppZoomOutController implements RemoteCallable<AppZoomOutController>,
        ShellTaskOrganizer.FocusListener, DisplayChangeController.OnDisplayChangingListener {

    private static final String TAG = "AppZoomOutController";

    private final Context mContext;
    private final ShellTaskOrganizer mTaskOrganizer;
    private final DisplayController mDisplayController;
    private final AppZoomOutDisplayAreaOrganizer mDisplayAreaOrganizer;
    private final ShellExecutor mMainExecutor;
    private final AppZoomOutImpl mImpl = new AppZoomOutImpl();

    private final DisplayController.OnDisplaysChangedListener mDisplaysChangedListener =
            new DisplayController.OnDisplaysChangedListener() {
                @Override
                public void onDisplayConfigurationChanged(int displayId, Configuration newConfig) {
                    if (displayId != DEFAULT_DISPLAY) {
                        return;
                    }
                    updateDisplayLayout(displayId);
                }

                @Override
                public void onDisplayAdded(int displayId) {
                    if (displayId != DEFAULT_DISPLAY) {
                        return;
                    }
                    updateDisplayLayout(displayId);
                }
            };


    public static AppZoomOutController create(Context context, ShellInit shellInit,
            ShellTaskOrganizer shellTaskOrganizer, DisplayController displayController,
            DisplayLayout displayLayout, @ShellMainThread ShellExecutor mainExecutor) {
        AppZoomOutDisplayAreaOrganizer displayAreaOrganizer = new AppZoomOutDisplayAreaOrganizer(
                context, displayLayout, mainExecutor);
        return new AppZoomOutController(context, shellInit, shellTaskOrganizer, displayController,
                displayAreaOrganizer, mainExecutor);
    }

    @VisibleForTesting
    AppZoomOutController(Context context, ShellInit shellInit,
            ShellTaskOrganizer shellTaskOrganizer, DisplayController displayController,
            AppZoomOutDisplayAreaOrganizer displayAreaOrganizer,
            @ShellMainThread ShellExecutor mainExecutor) {
        mContext = context;
        mTaskOrganizer = shellTaskOrganizer;
        mDisplayController = displayController;
        mDisplayAreaOrganizer = displayAreaOrganizer;
        mMainExecutor = mainExecutor;

        if (spatialModelAppPushback()) {
            shellInit.addInitCallback(this::onInit, this);
        }
    }

    private void onInit() {
        mTaskOrganizer.addFocusListener(this);

        mDisplayController.addDisplayWindowListener(mDisplaysChangedListener);
        mDisplayController.addDisplayChangingController(this);
        updateDisplayLayout(mContext.getDisplayId());

        mDisplayAreaOrganizer.registerOrganizer();
    }

    public AppZoomOut asAppZoomOut() {
        return mImpl;
    }

    public void setProgress(float progress) {
        mDisplayAreaOrganizer.setProgress(progress);
    }

    void updateDisplayLayout(int displayId) {
        final DisplayLayout newDisplayLayout = mDisplayController.getDisplayLayout(displayId);
        if (newDisplayLayout == null) {
            Slog.w(TAG, "Failed to get new DisplayLayout.");
            return;
        }
        mDisplayAreaOrganizer.setDisplayLayout(newDisplayLayout);
    }

    @Override
    public void onFocusTaskChanged(ActivityManager.RunningTaskInfo taskInfo) {
        if (taskInfo == null) {
            return;
        }
        if (taskInfo.getActivityType() == WindowConfiguration.ACTIVITY_TYPE_HOME) {
            mDisplayAreaOrganizer.setIsHomeTaskFocused(taskInfo.isFocused);
        }
    }

    @Override
    public void onDisplayChange(int displayId, int fromRotation, int toRotation,
            @Nullable DisplayAreaInfo newDisplayAreaInfo, WindowContainerTransaction wct) {
        // TODO: verify if there is synchronization issues.
        if (toRotation != ROTATION_UNDEFINED) {
            mDisplayAreaOrganizer.onRotateDisplay(mContext, toRotation);
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

    @ExternalThread
    private class AppZoomOutImpl implements AppZoomOut {
        @Override
        public void setProgress(float progress) {
            mMainExecutor.execute(() -> AppZoomOutController.this.setProgress(progress));
        }
    }
}
