/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.globalactions;

import android.app.Dialog;
import android.app.KeyguardManager;
import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Point;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.internal.colorextraction.drawable.GradientDrawable;
import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.plugins.GlobalActions;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardMonitor;

public class GlobalActionsImpl implements GlobalActions {

    private static final float SHUTDOWN_SCRIM_ALPHA = 0.95f;

    private final Context mContext;
    private final KeyguardMonitor mKeyguardMonitor;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private GlobalActionsDialog mGlobalActions;

    public GlobalActionsImpl(Context context) {
        mContext = context;
        mKeyguardMonitor = Dependency.get(KeyguardMonitor.class);
        mDeviceProvisionedController = Dependency.get(DeviceProvisionedController.class);
    }

    @Override
    public void showGlobalActions(GlobalActionsManager manager) {
        if (mGlobalActions == null) {
            mGlobalActions = new GlobalActionsDialog(mContext, manager);
        }
        mGlobalActions.showDialog(mKeyguardMonitor.isShowing(),
                mDeviceProvisionedController.isDeviceProvisioned());
    }

    @Override
    public void showShutdownUi(boolean isReboot, String reason) {
        GradientDrawable background = new GradientDrawable(mContext);
        background.setAlpha((int) (SHUTDOWN_SCRIM_ALPHA * 255));

        Dialog d = new Dialog(mContext,
                com.android.systemui.R.style.Theme_SystemUI_Dialog_GlobalActions);
        // Window initialization
        Window window = d.getWindow();
        window.getAttributes().width = ViewGroup.LayoutParams.MATCH_PARENT;
        window.getAttributes().height = ViewGroup.LayoutParams.MATCH_PARENT;
        window.setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY);
        window.requestFeature(Window.FEATURE_NO_TITLE);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND
                | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR);
        window.addFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        window.setBackgroundDrawable(background);
        window.setWindowAnimations(R.style.Animation_Toast);

        d.setContentView(R.layout.shutdown_dialog);
        d.setCancelable(false);

        int color = Utils.getColorAttr(mContext, com.android.systemui.R.attr.wallpaperTextColor);
        boolean onKeyguard = mContext.getSystemService(
                KeyguardManager.class).isKeyguardLocked();

        ProgressBar bar = d.findViewById(R.id.progress);
        bar.getIndeterminateDrawable().setTint(color);
        TextView message = d.findViewById(R.id.text1);
        message.setTextColor(color);
        if (isReboot) message.setText(R.string.reboot_to_reset_message);

        Point displaySize = new Point();
        mContext.getDisplay().getRealSize(displaySize);
        GradientColors colors = Dependency.get(SysuiColorExtractor.class).getColors(
                onKeyguard ? WallpaperManager.FLAG_LOCK : WallpaperManager.FLAG_SYSTEM);
        background.setColors(colors, false);
        background.setScreenSize(displaySize.x, displaySize.y);

        d.show();
    }
}
