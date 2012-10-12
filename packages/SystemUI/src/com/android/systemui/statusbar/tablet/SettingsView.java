/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.systemui.statusbar.tablet;

import android.app.StatusBarManager;
import android.content.Context;
import android.content.Intent;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Slog;
import android.widget.LinearLayout;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.AirplaneModeController;
import com.android.systemui.statusbar.policy.AutoRotateController;
import com.android.systemui.statusbar.policy.BrightnessController;
import com.android.systemui.statusbar.policy.DoNotDisturbController;
import com.android.systemui.statusbar.policy.ToggleSlider;
import com.android.systemui.statusbar.policy.VolumeController;

public class SettingsView extends LinearLayout implements View.OnClickListener {
    static final String TAG = "SettingsView";

    AirplaneModeController mAirplane;
    AutoRotateController mRotate;
    BrightnessController mBrightness;
    DoNotDisturbController mDoNotDisturb;
    View mRotationLockContainer;
    View mRotationLockSeparator;

    public SettingsView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public SettingsView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        final Context context = getContext();

        mAirplane = new AirplaneModeController(context,
                (CompoundButton)findViewById(R.id.airplane_checkbox));
        findViewById(R.id.network).setOnClickListener(this);

        mRotationLockContainer = findViewById(R.id.rotate);
        mRotationLockSeparator = findViewById(R.id.rotate_separator);
        mRotate = new AutoRotateController(context,
                (CompoundButton)findViewById(R.id.rotate_checkbox),
                new AutoRotateController.RotationLockCallbacks() {
                    @Override
                    public void setRotationLockControlVisibility(boolean show) {
                        mRotationLockContainer.setVisibility(show ? View.VISIBLE : View.GONE);
                        mRotationLockSeparator.setVisibility(show ? View.VISIBLE : View.GONE);
                    }
                });

        mBrightness = new BrightnessController(context,
                (ImageView)findViewById(R.id.brightness_icon),
                (ToggleSlider)findViewById(R.id.brightness));
        mDoNotDisturb = new DoNotDisturbController(context,
                (CompoundButton)findViewById(R.id.do_not_disturb_checkbox));
        findViewById(R.id.settings).setOnClickListener(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mAirplane.release();
        mDoNotDisturb.release();
        mRotate.release();
    }

    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.network:
                onClickNetwork();
                break;
            case R.id.settings:
                onClickSettings();
                break;
        }
    }

    private StatusBarManager getStatusBarManager() {
        return (StatusBarManager)getContext().getSystemService(Context.STATUS_BAR_SERVICE);
    }

    // Network
    // ----------------------------
    private void onClickNetwork() {
        getContext().startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        getStatusBarManager().collapsePanels();
    }

    // Settings
    // ----------------------------
    private void onClickSettings() {
        getContext().startActivityAsUser(new Intent(Settings.ACTION_SETTINGS)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                new UserHandle(UserHandle.USER_CURRENT));
        getStatusBarManager().collapsePanels();
    }
}

