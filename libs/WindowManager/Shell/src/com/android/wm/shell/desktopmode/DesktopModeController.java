/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.wm.shell.desktopmode;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;

import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_DESKTOP_MODE;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.UserHandle;
import android.provider.Settings;
import android.window.WindowContainerTransaction;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.RootDisplayAreaOrganizer;
import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.sysui.ShellInit;

/**
 * Handles windowing changes when desktop mode system setting changes
 */
public class DesktopModeController {

    private final Context mContext;
    private final ShellTaskOrganizer mShellTaskOrganizer;
    private final RootDisplayAreaOrganizer mRootDisplayAreaOrganizer;
    private final SettingsObserver mSettingsObserver;

    public DesktopModeController(Context context, ShellInit shellInit,
            ShellTaskOrganizer shellTaskOrganizer,
            RootDisplayAreaOrganizer rootDisplayAreaOrganizer,
            @ShellMainThread Handler mainHandler) {
        mContext = context;
        mShellTaskOrganizer = shellTaskOrganizer;
        mRootDisplayAreaOrganizer = rootDisplayAreaOrganizer;
        mSettingsObserver = new SettingsObserver(mContext, mainHandler);
        shellInit.addInitCallback(this::onInit, this);
    }

    private void onInit() {
        ProtoLog.d(WM_SHELL_DESKTOP_MODE, "Initialize DesktopModeController");
        mSettingsObserver.observe();
        if (DesktopMode.isActive(mContext)) {
            updateDesktopModeActive(true);
        }
    }

    @VisibleForTesting
    void updateDesktopModeActive(boolean active) {
        ProtoLog.d(WM_SHELL_DESKTOP_MODE, "updateDesktopModeActive: active=%s", active);

        int displayId = mContext.getDisplayId();

        WindowContainerTransaction wct = new WindowContainerTransaction();
        // Reset freeform windowing mode that is set per task level (tasks should inherit
        // container value)
        wct.merge(mShellTaskOrganizer.prepareClearFreeformForStandardTasks(displayId),
                true /* transfer */);
        int targetWindowingMode;
        if (active) {
            targetWindowingMode = WINDOWING_MODE_FREEFORM;
        } else {
            targetWindowingMode = WINDOWING_MODE_FULLSCREEN;
            // Clear any resized bounds
            wct.merge(mShellTaskOrganizer.prepareClearBoundsForStandardTasks(displayId),
                    true /* transfer */);
        }
        wct.merge(mRootDisplayAreaOrganizer.prepareWindowingModeChange(displayId,
                targetWindowingMode), true /* transfer */);
        mRootDisplayAreaOrganizer.applyTransaction(wct);
    }

    /**
     * A {@link ContentObserver} for listening to changes to {@link Settings.System#DESKTOP_MODE}
     */
    private final class SettingsObserver extends ContentObserver {

        private final Uri mDesktopModeSetting = Settings.System.getUriFor(
                Settings.System.DESKTOP_MODE);

        private final Context mContext;

        SettingsObserver(Context context, Handler handler) {
            super(handler);
            mContext = context;
        }

        public void observe() {
            // TODO(b/242867463): listen for setting change for all users
            mContext.getContentResolver().registerContentObserver(mDesktopModeSetting,
                    false /* notifyForDescendants */, this /* observer */, UserHandle.USER_CURRENT);
        }

        @Override
        public void onChange(boolean selfChange, @Nullable Uri uri) {
            if (mDesktopModeSetting.equals(uri)) {
                ProtoLog.d(WM_SHELL_DESKTOP_MODE, "Received update for desktop mode setting");
                desktopModeSettingChanged();
            }
        }

        private void desktopModeSettingChanged() {
            boolean enabled = DesktopMode.isActive(mContext);
            updateDesktopModeActive(enabled);
        }
    }
}
