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

import android.content.Context;
import android.content.res.Configuration;
import android.view.InsetsState;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayInsetsController;
import com.android.wm.shell.common.DisplayLayout;
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
        DisplayController.OnDisplaysChangedListener {
    private static final String TAG = PipController.class.getSimpleName();

    private Context mContext;
    private ShellController mShellController;
    private DisplayController mDisplayController;
    private DisplayInsetsController mDisplayInsetsController;
    private PipDisplayLayoutState mPipDisplayLayoutState;

    private PipController(Context context,
            ShellInit shellInit,
            ShellController shellController,
            DisplayController displayController,
            DisplayInsetsController displayInsetsController,
            PipDisplayLayoutState pipDisplayLayoutState) {
        mContext = context;
        mShellController = shellController;
        mDisplayController = displayController;
        mDisplayInsetsController = displayInsetsController;
        mPipDisplayLayoutState = pipDisplayLayoutState;

        if (PipUtils.isPip2ExperimentEnabled()) {
            shellInit.addInitCallback(this::onInit, this);
        }
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
    }

    /**
     * Instantiates {@link PipController}, returns {@code null} if the feature not supported.
     */
    public static PipController create(Context context,
            ShellInit shellInit,
            ShellController shellController,
            DisplayController displayController,
            DisplayInsetsController displayInsetsController,
            PipDisplayLayoutState pipDisplayLayoutState) {
        if (!context.getPackageManager().hasSystemFeature(FEATURE_PICTURE_IN_PICTURE)) {
            ProtoLog.w(ShellProtoLogGroup.WM_SHELL_PICTURE_IN_PICTURE,
                    "%s: Device doesn't support Pip feature", TAG);
            return null;
        }
        return new PipController(context, shellInit, shellController, displayController,
                displayInsetsController, pipDisplayLayoutState);
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
}
