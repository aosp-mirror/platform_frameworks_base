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

import static android.app.StatusBarManager.DISABLE2_GLOBAL_ACTIONS;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import android.app.Dialog;
import android.app.KeyguardManager;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.android.internal.R;
import com.android.internal.colorextraction.ColorExtractor.GradientColors;
import com.android.internal.colorextraction.drawable.ScrimDrawable;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.settingslib.Utils;
import com.android.systemui.Dependency;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.colorextraction.SysuiColorExtractor;
import com.android.systemui.plugins.GlobalActions;
import com.android.systemui.plugins.GlobalActionsPanelPlugin;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.ExtensionController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

public class GlobalActionsImpl implements GlobalActions, CommandQueue.Callbacks {

    private static final float SHUTDOWN_SCRIM_ALPHA = 0.95f;

    private final Context mContext;
    private final KeyguardStateController mKeyguardStateController;
    private final DeviceProvisionedController mDeviceProvisionedController;
    private final ExtensionController.Extension<GlobalActionsPanelPlugin> mPanelExtension;
    private GlobalActionsDialog mGlobalActions;
    private boolean mDisabled;

    public GlobalActionsImpl(Context context) {
        mContext = context;
        mKeyguardStateController = Dependency.get(KeyguardStateController.class);
        mDeviceProvisionedController = Dependency.get(DeviceProvisionedController.class);
        SysUiServiceProvider.getComponent(context, CommandQueue.class).addCallback(this);
        mPanelExtension = Dependency.get(ExtensionController.class)
                .newExtension(GlobalActionsPanelPlugin.class)
                .withPlugin(GlobalActionsPanelPlugin.class)
                .build();
    }

    @Override
    public void destroy() {
        SysUiServiceProvider.getComponent(mContext, CommandQueue.class).removeCallback(this);
        if (mGlobalActions != null) {
            mGlobalActions.destroy();
            mGlobalActions = null;
        }
    }

    @Override
    public void showGlobalActions(GlobalActionsManager manager) {
        if (mDisabled) return;
        if (mGlobalActions == null) {
            mGlobalActions = new GlobalActionsDialog(mContext, manager);
        }
        mGlobalActions.showDialog(mKeyguardStateController.isShowing(),
                mDeviceProvisionedController.isDeviceProvisioned(),
                mPanelExtension.get());
        Dependency.get(KeyguardUpdateMonitor.class).requestFaceAuth();
    }

    @Override
    public void showShutdownUi(boolean isReboot, String reason) {
        ScrimDrawable background = new ScrimDrawable();
        background.setAlpha((int) (SHUTDOWN_SCRIM_ALPHA * 255));

        Dialog d = new Dialog(mContext,
                com.android.systemui.R.style.Theme_SystemUI_Dialog_GlobalActions);
        // Window initialization
        Window window = d.getWindow();
        window.requestFeature(Window.FEATURE_NO_TITLE);
        window.getAttributes().systemUiVisibility |= View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
        // Inflate the decor view, so the attributes below are not overwritten by the theme.
        window.getDecorView();
        window.getAttributes().width = ViewGroup.LayoutParams.MATCH_PARENT;
        window.getAttributes().height = ViewGroup.LayoutParams.MATCH_PARENT;
        window.getAttributes().layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        window.setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        window.setBackgroundDrawable(background);
        window.setWindowAnimations(R.style.Animation_Toast);

        d.setContentView(R.layout.shutdown_dialog);
        d.setCancelable(false);

        int color = Utils.getColorAttrDefaultColor(mContext,
                com.android.systemui.R.attr.wallpaperTextColor);
        boolean onKeyguard = mContext.getSystemService(
                KeyguardManager.class).isKeyguardLocked();

        ProgressBar bar = d.findViewById(R.id.progress);
        bar.getIndeterminateDrawable().setTint(color);
        TextView message = d.findViewById(R.id.text1);
        message.setTextColor(color);
        if (isReboot) message.setText(R.string.reboot_to_reset_message);

        GradientColors colors = Dependency.get(SysuiColorExtractor.class).getNeutralColors();
        background.setColor(colors.getMainColor(), false);

        d.show();
    }

    @Override
    public void disable(int displayId, int state1, int state2, boolean animate) {
        final boolean disabled = (state2 & DISABLE2_GLOBAL_ACTIONS) != 0;
        if (displayId != mContext.getDisplayId() || disabled == mDisabled) return;
        mDisabled = disabled;
        if (disabled && mGlobalActions != null) {
            mGlobalActions.dismissDialog();
        }
    }
}
