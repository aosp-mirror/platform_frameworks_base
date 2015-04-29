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

package com.android.systemui.volume;

import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.media.VolumePolicy;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.WindowManager;

import com.android.systemui.SystemUI;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.qs.tiles.DndTile;
import com.android.systemui.statusbar.phone.PhoneStatusBar;
import com.android.systemui.statusbar.policy.ZenModeController;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Implementation of VolumeComponent backed by the new volume dialog.
 */
public class VolumeDialogComponent implements VolumeComponent {
    private final SystemUI mSysui;
    private final Context mContext;
    private final VolumeDialogController mController;
    private final ZenModeController mZenModeController;
    private final VolumeDialog mDialog;
    private final VolumePolicy mVolumePolicy = new VolumePolicy(
            true,  // volumeDownToEnterSilent
            true,  // volumeUpToExitSilent
            true,  // doNotDisturbWhenSilent
            400    // vibrateToSilentDebounce
    );

    public VolumeDialogComponent(SystemUI sysui, Context context, Handler handler,
            ZenModeController zen) {
        mSysui = sysui;
        mContext = context;
        mController = new VolumeDialogController(context, null) {
            @Override
            protected void onUserActivityW() {
                sendUserActivity();
            }
        };
        mZenModeController = zen;
        mDialog = new VolumeDialog(context, WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY,
                mController, zen, mVolumeDialogCallback);
        applyConfiguration();
    }

    private void sendUserActivity() {
        final KeyguardViewMediator kvm = mSysui.getComponent(KeyguardViewMediator.class);
        if (kvm != null) {
            kvm.userActivity();
        }
    }

    private void applyConfiguration() {
        mDialog.setStreamImportant(AudioManager.STREAM_ALARM, true);
        mDialog.setStreamImportant(AudioManager.STREAM_SYSTEM, false);
        mDialog.setShowHeaders(false);
        mDialog.setAutomute(true);
        mDialog.setSilentMode(false);
        mController.setVolumePolicy(mVolumePolicy);
        mController.showDndTile(true);
    }

    @Override
    public ZenModeController getZenController() {
        return mZenModeController;
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        // noop
    }

    @Override
    public void dismissNow() {
        mController.dismiss();
    }

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        // noop
    }

    @Override
    public void register() {
        mController.register();
        DndTile.setCombinedIcon(mContext, true);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mController.dump(fd, pw, args);
        mDialog.dump(pw);
    }

    private void startSettings(Intent intent) {
        mSysui.getComponent(PhoneStatusBar.class).startActivityDismissingKeyguard(intent,
                true /* onlyProvisioned */, true /* dismissShade */);
    }

    private final VolumeDialog.Callback mVolumeDialogCallback = new VolumeDialog.Callback() {
        @Override
        public void onSettingsClicked() {
            startSettings(new Intent(Settings.ACTION_NOTIFICATION_SETTINGS));
        }

        @Override
        public void onZenSettingsClicked() {
            startSettings(ZenModePanel.ZEN_SETTINGS);
        }

        @Override
        public void onZenPrioritySettingsClicked() {
            startSettings(ZenModePanel.ZEN_PRIORITY_SETTINGS);
        }
    };

}
