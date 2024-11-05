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

package com.android.systemui.globalactions;

import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;

import android.annotation.Nullable;
import android.annotation.StringRes;
import android.app.Dialog;
import android.content.Context;
import android.nearby.NearbyManager;
import android.net.platform.flags.Flags;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.VisibleForTesting;

import com.android.internal.R;
import com.android.systemui.scrim.ScrimDrawable;

import javax.inject.Inject;

/**
 * Provides the UI shown during system shutdown.
 */
public class ShutdownUi {

    private Context mContext;
    private NearbyManager mNearbyManager;

    @Inject
    public ShutdownUi(Context context, NearbyManager nearbyManager) {
        mContext = context;
        mNearbyManager = nearbyManager;
    }

    /**
     * Display the shutdown UI.
     * @param isReboot Whether the device will be rebooting after this shutdown.
     * @param reason Cause for the shutdown.
     * @return Shutdown dialog.
     */
    public Dialog showShutdownUi(boolean isReboot, String reason) {
        ScrimDrawable background = new ScrimDrawable();

        final Dialog d = new Dialog(mContext,
                com.android.systemui.res.R.style.Theme_SystemUI_Dialog_GlobalActions);

        float backgroundAlpha = mContext.getResources().getFloat(
                com.android.systemui.res.R.dimen.shutdown_scrim_behind_alpha);
        background.setAlpha((int) (backgroundAlpha * 255));

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
        window.getAttributes().setFitInsetsTypes(0 /* types */);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.addFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        window.setBackgroundDrawable(background);
        window.setWindowAnimations(com.android.systemui.res.R.style.Animation_ShutdownUi);

        d.setContentView(getShutdownDialogContent(isReboot));
        d.setCancelable(false);

        int color = mContext.getResources().getColor(
                com.android.systemui.res.R.color.global_actions_shutdown_ui_text,
                mContext.getTheme());

        ProgressBar bar = d.findViewById(R.id.progress);
        bar.getIndeterminateDrawable().setTint(color);

        TextView reasonView = d.findViewById(R.id.text1);
        TextView messageView = d.findViewById(R.id.text2);

        reasonView.setTextColor(color);
        messageView.setTextColor(color);

        messageView.setText(getRebootMessage(isReboot, reason));
        String rebootReasonMessage = getReasonMessage(reason);
        if (rebootReasonMessage != null) {
            reasonView.setVisibility(View.VISIBLE);
            reasonView.setText(rebootReasonMessage);
        }

        d.show();

        return d;
    }

    /**
     * Returns the layout resource to use for UI while shutting down.
     * @param isReboot Whether this is a reboot or a shutdown.
     */
    @VisibleForTesting int getShutdownDialogContent(boolean isReboot) {
        if (!Flags.poweredOffFindingPlatform()) {
            return R.layout.shutdown_dialog;
        }
        int finderActive = mNearbyManager.getPoweredOffFindingMode();
        if (finderActive == NearbyManager.POWERED_OFF_FINDING_MODE_DISABLED
                || finderActive == NearbyManager.POWERED_OFF_FINDING_MODE_UNSUPPORTED) {
            // inactive or unsupported, use regular shutdown dialog
            return R.layout.shutdown_dialog;
        } else if (finderActive == NearbyManager.POWERED_OFF_FINDING_MODE_ENABLED) {
            // active, use dialog with finder info if shutting down
            return isReboot ? R.layout.shutdown_dialog :
                    com.android.systemui.res.R.layout.shutdown_dialog_finder_active;
        } else {
            // that's weird? default to regular dialog
            Log.w("ShutdownUi", "Unexpected value for finder active: " + finderActive);
            return R.layout.shutdown_dialog;
        }
    }

    @StringRes
    @VisibleForTesting int getRebootMessage(boolean isReboot, @Nullable String reason) {
        if (reason != null && reason.startsWith(PowerManager.REBOOT_RECOVERY_UPDATE)) {
            return R.string.reboot_to_update_reboot;
        } else if (reason != null && reason.equals(PowerManager.REBOOT_RECOVERY)) {
            return R.string.reboot_to_reset_message;
        } else if (isReboot) {
            return R.string.reboot_to_reset_message;
        } else {
            return R.string.shutdown_progress;
        }
    }

    @Nullable
    @VisibleForTesting String getReasonMessage(@Nullable String reason) {
        if (reason != null && reason.startsWith(PowerManager.REBOOT_RECOVERY_UPDATE)) {
            return mContext.getString(R.string.reboot_to_update_title);
        } else if (reason != null && reason.equals(PowerManager.REBOOT_RECOVERY)) {
            return mContext.getString(R.string.reboot_to_reset_title);
        } else {
            return null;
        }
    }
}
