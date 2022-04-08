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
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.media.VolumePolicy;
import android.os.Bundle;
import android.provider.Settings;
import android.view.WindowManager.LayoutParams;

import com.android.settingslib.applications.InterestingConfigChanges;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.demomode.DemoMode;
import com.android.systemui.demomode.DemoModeController;
import com.android.systemui.keyguard.KeyguardViewMediator;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.plugins.PluginDependencyProvider;
import com.android.systemui.plugins.VolumeDialog;
import com.android.systemui.plugins.VolumeDialogController;
import com.android.systemui.qs.tiles.DndTile;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.tuner.TunerService;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;

/**
 * Implementation of VolumeComponent backed by the new volume dialog.
 */
@SysUISingleton
public class VolumeDialogComponent implements VolumeComponent, TunerService.Tunable,
        VolumeDialogControllerImpl.UserActivityListener{

    public static final String VOLUME_DOWN_SILENT = "sysui_volume_down_silent";
    public static final String VOLUME_UP_SILENT = "sysui_volume_up_silent";
    public static final String VOLUME_SILENT_DO_NOT_DISTURB = "sysui_do_not_disturb";

    public static final boolean DEFAULT_VOLUME_DOWN_TO_ENTER_SILENT = false;
    public static final boolean DEFAULT_VOLUME_UP_TO_EXIT_SILENT = false;
    public static final boolean DEFAULT_DO_NOT_DISTURB_WHEN_SILENT = false;

    private static final Intent ZEN_SETTINGS =
            new Intent(Settings.ACTION_ZEN_MODE_SETTINGS);
    private static final Intent ZEN_PRIORITY_SETTINGS =
            new Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS);

    protected final Context mContext;
    private final VolumeDialogControllerImpl mController;
    private final InterestingConfigChanges mConfigChanges = new InterestingConfigChanges(
            ActivityInfo.CONFIG_FONT_SCALE | ActivityInfo.CONFIG_LOCALE
            | ActivityInfo.CONFIG_ASSETS_PATHS | ActivityInfo.CONFIG_UI_MODE);
    private final KeyguardViewMediator mKeyguardViewMediator;
    private final ActivityStarter mActivityStarter;
    private VolumeDialog mDialog;
    private VolumePolicy mVolumePolicy = new VolumePolicy(
            DEFAULT_VOLUME_DOWN_TO_ENTER_SILENT,  // volumeDownToEnterSilent
            DEFAULT_VOLUME_UP_TO_EXIT_SILENT,  // volumeUpToExitSilent
            DEFAULT_DO_NOT_DISTURB_WHEN_SILENT,  // doNotDisturbWhenSilent
            400    // vibrateToSilentDebounce
    );

    @Inject
    public VolumeDialogComponent(
            Context context,
            KeyguardViewMediator keyguardViewMediator,
            ActivityStarter activityStarter,
            VolumeDialogControllerImpl volumeDialogController,
            DemoModeController demoModeController,
            PluginDependencyProvider pluginDependencyProvider,
            ExtensionController extensionController,
            TunerService tunerService,
            VolumeDialog volumeDialog) {
        mContext = context;
        mKeyguardViewMediator = keyguardViewMediator;
        mActivityStarter = activityStarter;
        mController = volumeDialogController;
        mController.setUserActivityListener(this);
        // Allow plugins to reference the VolumeDialogController.
        pluginDependencyProvider.allowPluginDependency(VolumeDialogController.class);
        extensionController.newExtension(VolumeDialog.class)
                .withPlugin(VolumeDialog.class)
                .withDefault(() -> volumeDialog)
                .withCallback(dialog -> {
                    if (mDialog != null) {
                        mDialog.destroy();
                    }
                    mDialog = dialog;
                    mDialog.init(LayoutParams.TYPE_VOLUME_OVERLAY, mVolumeDialogCallback);
                }).build();
        applyConfiguration();
        tunerService.addTunable(this, VOLUME_DOWN_SILENT, VOLUME_UP_SILENT,
                VOLUME_SILENT_DO_NOT_DISTURB);
        demoModeController.addCallback(this);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        boolean volumeDownToEnterSilent = mVolumePolicy.volumeDownToEnterSilent;
        boolean volumeUpToExitSilent = mVolumePolicy.volumeUpToExitSilent;
        boolean doNotDisturbWhenSilent = mVolumePolicy.doNotDisturbWhenSilent;

        if (VOLUME_DOWN_SILENT.equals(key)) {
            volumeDownToEnterSilent =
                TunerService.parseIntegerSwitch(newValue, DEFAULT_VOLUME_DOWN_TO_ENTER_SILENT);
        } else if (VOLUME_UP_SILENT.equals(key)) {
            volumeUpToExitSilent =
                TunerService.parseIntegerSwitch(newValue, DEFAULT_VOLUME_UP_TO_EXIT_SILENT);
        } else if (VOLUME_SILENT_DO_NOT_DISTURB.equals(key)) {
            doNotDisturbWhenSilent =
                TunerService.parseIntegerSwitch(newValue, DEFAULT_DO_NOT_DISTURB_WHEN_SILENT);
        }

        setVolumePolicy(volumeDownToEnterSilent, volumeUpToExitSilent, doNotDisturbWhenSilent,
                mVolumePolicy.vibrateToSilentDebounce);
    }

    private void setVolumePolicy(boolean volumeDownToEnterSilent, boolean volumeUpToExitSilent,
            boolean doNotDisturbWhenSilent, int vibrateToSilentDebounce) {
        mVolumePolicy = new VolumePolicy(volumeDownToEnterSilent, volumeUpToExitSilent,
                doNotDisturbWhenSilent, vibrateToSilentDebounce);
        mController.setVolumePolicy(mVolumePolicy);
    }

    void setEnableDialogs(boolean volumeUi, boolean safetyWarning) {
        mController.setEnableDialogs(volumeUi, safetyWarning);
    }

    @Override
    public void onUserActivity() {
        mKeyguardViewMediator.userActivity();
    }

    private void applyConfiguration() {
        mController.setVolumePolicy(mVolumePolicy);
        mController.showDndTile(true);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (mConfigChanges.applyNewConfig(mContext.getResources())) {
            mController.mCallbacks.onConfigurationChanged();
        }
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
    public List<String> demoCommands() {
        List<String> s = new ArrayList<>();
        s.add(DemoMode.COMMAND_VOLUME);
        return s;
    }

    @Override
    public void register() {
        mController.register();
        DndTile.setCombinedIcon(mContext, true);
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
    }

    private void startSettings(Intent intent) {
        mActivityStarter.startActivity(intent, true /* onlyProvisioned */, true /* dismissShade */);
    }

    private final VolumeDialogImpl.Callback mVolumeDialogCallback = new VolumeDialogImpl.Callback() {
        @Override
        public void onZenSettingsClicked() {
            startSettings(ZEN_SETTINGS);
        }

        @Override
        public void onZenPrioritySettingsClicked() {
            startSettings(ZEN_PRIORITY_SETTINGS);
        }
    };

}
