/*
 * Copyright (C) 2021 The Android Open Source Project
 *           (C) 2022 Paranoid Android
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
 *
 * Changes from Qualcomm Innovation Center are provided under the following license:
 *
 * Copyright (c) 2022 Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause-Clear
 */

package com.android.systemui.qs.tiles.dialog;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.WorkerThread;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.settingslib.Utils;
import com.android.settingslib.bluetooth.CachedBluetoothDevice;
import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.animation.ActivityLaunchAnimator;
import com.android.systemui.animation.DialogLaunchAnimator;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.dagger.qualifiers.Background;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.statusbar.phone.SystemUIDialog;
import com.android.systemui.statusbar.policy.BluetoothController;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Dialog for bluetooth
 */
@SysUISingleton
public class BluetoothDialog extends SystemUIDialog implements Window.Callback {
    private static final String TAG = "BluetoothDialog";
    private static final boolean DEBUG = true;

    public static final int MAX_DEVICES_COUNT = 4;
    private static final String SAVED_DEVICES_INTENT = "android.settings.SAVED_DEVICES";

    private BluetoothViewAdapter mAdapter;
    private BluetoothController mBluetoothController;
    private BluetoothDialogFactory mBluetoothDialogFactory;
    private Context mContext;
    private Handler mHandler;
    private View mDialogView;
    private TextView mBluetoothDialogTitle;
    private TextView mBluetoothDialogSubTitle;
    private TextView mBluetoothToggleText;
    private Switch mBluetoothToggle;
    private ProgressBar mProgressBar;
    private View mDivider;
    private LinearLayout mTurnOnLayout;
    private LinearLayout mSeeAllLayout;
    private RecyclerView mBluetoothRecyclerView;
    private Button mDoneButton;
    private Button mSettingsButton;
    private DialogLaunchAnimator mDialogLaunchAnimator;
    private ActivityStarter mActivityStarter;

    private Drawable mBackgroundOn;
    private Drawable mBackgroundOff;

    private final BluetoothController.Callback mCallback = new BluetoothController.Callback() {
        @Override
        public void onBluetoothStateChange(boolean enabled) {
            if (DEBUG) {
                Log.i(TAG, "onBluetoothStateChange enabled=" + enabled);
            }
            mBluetoothToggle.setChecked(enabled);
            mHandler.post(() -> updateDialog());
        }

        @Override
        public void onBluetoothDevicesChanged() {
            if (DEBUG) {
                Log.i(TAG, "onBluetoothDevicesChanged");
            }
            mHandler.post(() -> updateDialog());
        }
    };

    public BluetoothDialog(Context context, BluetoothDialogFactory bluetoothDialogFactory,
            boolean aboveStatusBar, @Main Handler handler, ActivityStarter activityStarter,
            DialogLaunchAnimator dialogLaunchAnimator, BluetoothController bluetoothController) {
        super(context);
        if (DEBUG) {
            Log.d(TAG, "Init BluetoothDialog");
        }

        // Save the context that is wrapped with our theme.
        mContext = getContext();
        mHandler = handler;
        mBluetoothDialogFactory = bluetoothDialogFactory;
        mBluetoothController = bluetoothController;
        mActivityStarter = activityStarter;
        mDialogLaunchAnimator = dialogLaunchAnimator;
        mAdapter = new BluetoothViewAdapter(this);

        if (!aboveStatusBar) {
            getWindow().setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (DEBUG) {
            Log.d(TAG, "onCreate");
        }
        mDialogView = LayoutInflater.from(mContext).inflate(R.layout.bluetooth_dialog, null);
        final Window window = getWindow();
        window.setContentView(mDialogView);
        window.setWindowAnimations(R.style.Animation_InternetDialog);

        mBluetoothDialogTitle = mDialogView.requireViewById(R.id.bluetooth_dialog_title);
        mBluetoothDialogSubTitle = mDialogView.requireViewById(R.id.bluetooth_dialog_subtitle);
        mProgressBar = mDialogView.requireViewById(R.id.bluetooth_progress);
        mDivider = mDialogView.requireViewById(R.id.divider);
        mBluetoothToggle = mDialogView.requireViewById(R.id.bluetooth_toggle);
        mBluetoothToggleText = mDialogView.requireViewById(R.id.bluetooth_toggle_title);
        mBluetoothRecyclerView = mDialogView.requireViewById(R.id.bluetooth_list_layout);
        mSeeAllLayout = mDialogView.requireViewById(R.id.see_all_layout);
        mTurnOnLayout = mDialogView.requireViewById(R.id.turn_on_bluetooth_layout);
        mDoneButton = mDialogView.requireViewById(R.id.done_button);
        mSettingsButton = mDialogView.requireViewById(R.id.settings_button);
        mBackgroundOn = mContext.getDrawable(R.drawable.settingslib_switch_bar_bg_on);

        TypedArray typedArray = mContext.obtainStyledAttributes(
                new int[]{android.R.attr.selectableItemBackground});
        try {
            mBackgroundOff = typedArray.getDrawable(0 /* index */);
        } finally {
            typedArray.recycle();
        }

        mBluetoothToggle.setOnCheckedChangeListener(
                (buttonView, isChecked) -> {
                    mBluetoothController.setBluetoothEnabled(isChecked);
                });
        mSeeAllLayout.setOnClickListener(v -> {
            startActivity(new Intent(SAVED_DEVICES_INTENT)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), v);
        });
        mDoneButton.setOnClickListener(v -> dismissDialog());
        mSettingsButton.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), v);
        });
        mBluetoothRecyclerView.setLayoutManager(new LinearLayoutManager(mContext));
        mBluetoothRecyclerView.setAdapter(mAdapter);
    }

    @Override
    public void onStart() {
        super.onStart();
        if (DEBUG) {
            Log.d(TAG, "onStart");
        }
        mBluetoothController.addCallback(mCallback);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (DEBUG) {
            Log.d(TAG, "onStop");
        }
        mBluetoothController.removeCallback(mCallback);
        mSeeAllLayout.setOnClickListener(null);
        mBluetoothToggle.setOnCheckedChangeListener(null);
        mDoneButton.setOnClickListener(null);
        mSettingsButton.setOnClickListener(null);
        mBluetoothDialogFactory.destroyDialog();
    }

    public void dismissDialog() {
        if (DEBUG) {
            Log.d(TAG, "dismissDialog");
        }
        mBluetoothDialogFactory.destroyDialog();
        dismiss();
    }

    void startActivity(Intent intent, View view) {
        ActivityLaunchAnimator.Controller controller =
                mDialogLaunchAnimator.createActivityLaunchController(view);

        if (controller == null) {
            dismissDialog();
        }

        mActivityStarter.postStartActivityDismissingKeyguard(intent, 0, controller);
    }

    /**
     * Update the bluetooth dialog when receiving the callback.
     */
    void updateDialog() {
        if (DEBUG) {
            Log.d(TAG, "updateDialog");
        }
        // subtitle
        int subtitle = R.string.bluetooth_is_off;
        boolean enabled = mBluetoothController.isBluetoothEnabled();
        boolean connecting = mBluetoothController.isBluetoothConnecting();
        boolean turningOn =
                mBluetoothController.getBluetoothState() == BluetoothAdapter.STATE_TURNING_ON;
        if (connecting) {
            subtitle = R.string.quick_settings_connecting;
        } else if (turningOn) {
            subtitle = R.string.quick_settings_bluetooth_secondary_label_transient;
        } else if (enabled) {
            subtitle = R.string.tap_a_device_to_connect;
        }
        mBluetoothDialogSubTitle.setText(mContext.getString(subtitle));

        // progress bar
        boolean showProgress = connecting || turningOn;
        mProgressBar.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        mDivider.setVisibility(showProgress ? View.GONE : View.VISIBLE);

        // devices
        Collection<CachedBluetoothDevice> devices = mBluetoothController.getDevices();
        if (!enabled || devices == null) {
            mBluetoothRecyclerView.setVisibility(View.GONE);
            mSeeAllLayout.setVisibility(View.GONE);
            updateTurnOnLayout(true);
            return;
        }
        boolean isOnCall = Utils.isAudioModeOngoingCall(mContext);
        CachedBluetoothDevice activeDevice =
                devices.stream()
                        .filter(device ->
                            (device.isActiveDevice(BluetoothProfile.HEADSET) && isOnCall)
                            || (device.isActiveDevice(BluetoothProfile.A2DP) && !isOnCall)
                            || device.isActiveDevice(BluetoothProfile.HEARING_AID)
                            || device.isActiveDevice(BluetoothProfile.LE_AUDIO)
                        )
                        .findFirst()
                        .orElse(null);
        mBluetoothRecyclerView.setVisibility(View.VISIBLE);
        mAdapter.setBluetoothDevices(new ArrayList(devices));
        mAdapter.setActiveDevice(activeDevice);
        mSeeAllLayout.setVisibility(devices.size() > MAX_DEVICES_COUNT ? View.VISIBLE : View.GONE);
        updateTurnOnLayout(activeDevice == null);
    }

    private void updateTurnOnLayout(boolean showBackground) {
        ViewGroup.LayoutParams lp = mTurnOnLayout.getLayoutParams();
        lp.height = mContext.getResources().getDimensionPixelSize(
                R.dimen.internet_dialog_wifi_network_height);
        mTurnOnLayout.setLayoutParams(lp);
        mTurnOnLayout.setBackground(showBackground ? mBackgroundOn : null);
        mBluetoothToggleText.setTextAppearance(showBackground
                    ? R.style.TextAppearance_InternetDialog_Active
                    : R.style.TextAppearance_InternetDialog);
    }
}
