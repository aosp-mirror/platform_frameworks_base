/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.sensorprivacy.television;

import static android.hardware.SensorPrivacyManager.Sensors.CAMERA;
import static android.hardware.SensorPrivacyManager.Sensors.MICROPHONE;
import static android.hardware.SensorPrivacyManager.Sources.OTHER;

import android.annotation.DimenRes;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.hardware.SensorPrivacyManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController;
import com.android.systemui.tv.TvBottomSheetActivity;

import javax.inject.Inject;

/**
 * Bottom sheet that is shown when the camera/mic sensors are blocked by the global software toggle
 * or physical privacy switch.
 */
public class TvUnblockSensorActivity extends TvBottomSheetActivity {

    private static final String TAG = TvUnblockSensorActivity.class.getSimpleName();
    private static final String ACTION_MANAGE_CAMERA_PRIVACY =
            "android.settings.MANAGE_CAMERA_PRIVACY";
    private static final String ACTION_MANAGE_MICROPHONE_PRIVACY =
            "android.settings.MANAGE_MICROPHONE_PRIVACY";

    private static final int ALL_SENSORS = Integer.MAX_VALUE;

    private int mSensor = -1;

    private final IndividualSensorPrivacyController mSensorPrivacyController;
    private IndividualSensorPrivacyController.Callback mSensorPrivacyCallback;
    private TextView mTitle;
    private TextView mContent;
    private ImageView mIcon;
    private ImageView mSecondIcon;
    private Button mPositiveButton;
    private Button mCancelButton;

    @Inject
    public TvUnblockSensorActivity(
            IndividualSensorPrivacyController individualSensorPrivacyController) {
        mSensorPrivacyController = individualSensorPrivacyController;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addSystemFlags(
                WindowManager.LayoutParams.SYSTEM_FLAG_HIDE_NON_SYSTEM_OVERLAY_WINDOWS);

        boolean allSensors = getIntent().getBooleanExtra(SensorPrivacyManager.EXTRA_ALL_SENSORS,
                false);
        if (allSensors) {
            mSensor = ALL_SENSORS;
        } else {
            mSensor = getIntent().getIntExtra(SensorPrivacyManager.EXTRA_SENSOR, -1);
        }

        if (mSensor == -1) {
            Log.v(TAG, "Invalid extras");
            finish();
            return;
        }

        mSensorPrivacyCallback = (sensor, blocked) -> {
            if (mSensor == ALL_SENSORS && !mSensorPrivacyController.isSensorBlocked(CAMERA)
                    && !mSensorPrivacyController.isSensorBlocked(MICROPHONE)) {
                showToastAndFinish();
            } else if (this.mSensor == sensor && !blocked) {
                showToastAndFinish();
            } else {
                updateUI();
            }
        };

        initUI();
    }

    private void showToastAndFinish() {
        final int toastMsgResId;
        switch(mSensor) {
            case MICROPHONE:
                toastMsgResId = R.string.sensor_privacy_mic_unblocked_toast_content;
                break;
            case CAMERA:
                toastMsgResId = R.string.sensor_privacy_camera_unblocked_toast_content;
                break;
            case ALL_SENSORS:
            default:
                toastMsgResId = R.string.sensor_privacy_mic_camera_unblocked_toast_content;
                break;
        }
        Toast.makeText(this, toastMsgResId, Toast.LENGTH_SHORT).show();
        finish();
    }

    private boolean isBlockedByHardwareToggle() {
        if (mSensor == ALL_SENSORS) {
            return mSensorPrivacyController.isSensorBlockedByHardwareToggle(CAMERA)
                    || mSensorPrivacyController.isSensorBlockedByHardwareToggle(MICROPHONE);
        } else {
            return mSensorPrivacyController.isSensorBlockedByHardwareToggle(mSensor);
        }
    }

    private void initUI() {
        mTitle = findViewById(R.id.bottom_sheet_title);
        mContent = findViewById(R.id.bottom_sheet_body);
        mIcon = findViewById(R.id.bottom_sheet_icon);
        // mic icon if both icons are shown
        mSecondIcon = findViewById(R.id.bottom_sheet_second_icon);
        mPositiveButton = findViewById(R.id.bottom_sheet_positive_button);
        mCancelButton = findViewById(R.id.bottom_sheet_negative_button);

        mCancelButton.setText(android.R.string.cancel);
        mCancelButton.setOnClickListener(v -> finish());

        updateUI();
    }

    private void updateUI() {
        if (isBlockedByHardwareToggle()) {
            updateUiForHardwareToggle();
        } else {
            updateUiForSoftwareToggle();
        }
    }

    private void updateUiForHardwareToggle() {
        final Resources resources = getResources();

        boolean micBlocked = (mSensor == MICROPHONE || mSensor == ALL_SENSORS)
                && mSensorPrivacyController.isSensorBlockedByHardwareToggle(MICROPHONE);
        boolean cameraBlocked = (mSensor == CAMERA || mSensor == ALL_SENSORS)
                && mSensorPrivacyController.isSensorBlockedByHardwareToggle(CAMERA);

        setIconTint(resources.getBoolean(R.bool.config_unblockHwSensorIconEnableTint));
        setIconSize(R.dimen.unblock_hw_sensor_icon_width, R.dimen.unblock_hw_sensor_icon_height);

        if (micBlocked && cameraBlocked) {
            mTitle.setText(R.string.sensor_privacy_start_use_mic_camera_blocked_dialog_title);
            mContent.setText(
                    R.string.sensor_privacy_start_use_mic_camera_blocked_dialog_content);
            mIcon.setImageResource(R.drawable.unblock_hw_sensor_all);

            Drawable secondIconDrawable = resources.getDrawable(
                    R.drawable.unblock_hw_sensor_all_second, getTheme());
            if (secondIconDrawable == null) {
                mSecondIcon.setVisibility(View.GONE);
            } else {
                mSecondIcon.setImageDrawable(secondIconDrawable);
            }
        } else if (cameraBlocked) {
            mTitle.setText(R.string.sensor_privacy_start_use_camera_blocked_dialog_title);
            mContent.setText(R.string.sensor_privacy_start_use_camera_blocked_dialog_content);
            mIcon.setImageResource(R.drawable.unblock_hw_sensor_camera);
            mSecondIcon.setVisibility(View.GONE);
        } else if (micBlocked) {
            mTitle.setText(R.string.sensor_privacy_start_use_mic_blocked_dialog_title);
            mContent.setText(R.string.sensor_privacy_start_use_mic_blocked_dialog_content);
            mIcon.setImageResource(R.drawable.unblock_hw_sensor_microphone);
            mSecondIcon.setVisibility(View.GONE);
        }

        // Start animation if drawable is animated
        Drawable iconDrawable = mIcon.getDrawable();
        if (iconDrawable instanceof Animatable) {
            ((Animatable) iconDrawable).start();
        }

        mPositiveButton.setVisibility(View.GONE);
        mCancelButton.setText(android.R.string.ok);
    }

    private void updateUiForSoftwareToggle() {
        setIconTint(true);
        setIconSize(R.dimen.bottom_sheet_icon_size, R.dimen.bottom_sheet_icon_size);

        switch (mSensor) {
            case MICROPHONE:
                mTitle.setText(R.string.sensor_privacy_start_use_mic_dialog_title);
                mContent.setText(R.string.sensor_privacy_start_use_mic_dialog_content);
                mIcon.setImageResource(com.android.internal.R.drawable.perm_group_microphone);
                mSecondIcon.setVisibility(View.GONE);
                break;
            case CAMERA:
                mTitle.setText(R.string.sensor_privacy_start_use_camera_dialog_title);
                mContent.setText(R.string.sensor_privacy_start_use_camera_dialog_content);
                mIcon.setImageResource(com.android.internal.R.drawable.perm_group_camera);
                mSecondIcon.setVisibility(View.GONE);
                break;
            case ALL_SENSORS:
            default:
                mTitle.setText(R.string.sensor_privacy_start_use_mic_camera_dialog_title);
                mContent.setText(R.string.sensor_privacy_start_use_mic_camera_dialog_content);
                mIcon.setImageResource(com.android.internal.R.drawable.perm_group_camera);
                mSecondIcon.setImageResource(
                        com.android.internal.R.drawable.perm_group_microphone);
                break;
        }

        mPositiveButton.setText(
                com.android.internal.R.string.sensor_privacy_start_use_dialog_turn_on_button);
        mPositiveButton.setOnClickListener(v -> {
            if (mSensor == ALL_SENSORS) {
                mSensorPrivacyController.setSensorBlocked(OTHER, CAMERA, false);
                mSensorPrivacyController.setSensorBlocked(OTHER, MICROPHONE, false);
            } else {
                mSensorPrivacyController.setSensorBlocked(OTHER, mSensor, false);
            }
        });
    }

    private void setIconTint(boolean enableTint) {
        final Resources resources = getResources();

        if (enableTint) {
            final ColorStateList iconTint = resources.getColorStateList(
                    R.color.bottom_sheet_icon_color, getTheme());
            mIcon.setImageTintList(iconTint);
            mSecondIcon.setImageTintList(iconTint);
        } else {
            mIcon.setImageTintList(null);
            mSecondIcon.setImageTintList(null);
        }

        mIcon.invalidate();
        mSecondIcon.invalidate();
    }

    private void setIconSize(@DimenRes int widthRes, @DimenRes int heightRes) {
        final Resources resources = getResources();
        final int iconWidth = resources.getDimensionPixelSize(widthRes);
        final int iconHeight = resources.getDimensionPixelSize(heightRes);

        mIcon.getLayoutParams().width = iconWidth;
        mIcon.getLayoutParams().height = iconHeight;
        mIcon.invalidate();

        mSecondIcon.getLayoutParams().width = iconWidth;
        mSecondIcon.getLayoutParams().height = iconHeight;
        mSecondIcon.invalidate();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateUI();
        mSensorPrivacyController.addCallback(mSensorPrivacyCallback);
    }

    @Override
    public void onPause() {
        mSensorPrivacyController.removeCallback(mSensorPrivacyCallback);
        super.onPause();
    }

}
