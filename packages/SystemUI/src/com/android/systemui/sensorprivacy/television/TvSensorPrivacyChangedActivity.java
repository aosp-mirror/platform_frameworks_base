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

package com.android.systemui.sensorprivacy.television;

import static android.hardware.SensorPrivacyManager.Sensors.CAMERA;
import static android.hardware.SensorPrivacyManager.Sensors.MICROPHONE;

import android.annotation.DimenRes;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.hardware.SensorPrivacyManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController;
import com.android.systemui.tv.TvBottomSheetActivity;
import com.android.systemui.util.settings.GlobalSettings;

import javax.inject.Inject;

/**
 * Bottom sheet that is shown when the camera/mic sensors privacy state changed
 * by the global software toggle or physical privacy switch.
 */
public class TvSensorPrivacyChangedActivity extends TvBottomSheetActivity {

    private static final String TAG = TvSensorPrivacyChangedActivity.class.getSimpleName();

    private static final int ALL_SENSORS = Integer.MAX_VALUE;

    private int mSensor = -1;
    private int mToggleType = -1;

    private final GlobalSettings mGlobalSettings;
    private final IndividualSensorPrivacyController mSensorPrivacyController;
    private IndividualSensorPrivacyController.Callback mSensorPrivacyCallback;
    private TextView mTitle;
    private TextView mContent;
    private ImageView mIcon;
    private ImageView mSecondIcon;
    private Button mPositiveButton;
    private Button mCancelButton;

    @Inject
    public TvSensorPrivacyChangedActivity(
            IndividualSensorPrivacyController individualSensorPrivacyController,
            GlobalSettings globalSettings) {
        mSensorPrivacyController = individualSensorPrivacyController;
        mGlobalSettings = globalSettings;
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

        mToggleType = getIntent().getIntExtra(SensorPrivacyManager.EXTRA_TOGGLE_TYPE, -1);

        if (mSensor == -1 || mToggleType == -1) {
            Log.v(TAG, "Invalid extras");
            finish();
            return;
        }

        // Do not show for software toggles
        if (mToggleType == SensorPrivacyManager.TOGGLE_TYPE_SOFTWARE) {
            finish();
            return;
        }

        mSensorPrivacyCallback = (sensor, blocked) -> {
            updateUI();
        };

        initUI();
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
        final Resources resources = getResources();
        setIconTint(resources.getBoolean(R.bool.config_unblockHwSensorIconEnableTint));
        setIconSize(R.dimen.unblock_hw_sensor_icon_width, R.dimen.unblock_hw_sensor_icon_height);

        switch (mSensor) {
            case CAMERA:
                updateUiForCameraUpdate(
                        mSensorPrivacyController.isSensorBlockedByHardwareToggle(CAMERA));
                break;
            case MICROPHONE:
            default:
                updateUiForMicUpdate(
                        mSensorPrivacyController.isSensorBlockedByHardwareToggle(MICROPHONE));
                break;
        }

        // Start animation if drawable is animated
        Drawable iconDrawable = mIcon.getDrawable();
        if (iconDrawable instanceof Animatable) {
            ((Animatable) iconDrawable).start();
        }

        mPositiveButton.setVisibility(View.GONE);
        mCancelButton.setText(android.R.string.ok);
    }

    private void updateUiForMicUpdate(boolean blocked) {
        if (blocked) {
            mTitle.setText(R.string.sensor_privacy_mic_turned_off_dialog_title);
            if (isExplicitUserInteractionAudioBypassAllowed()) {
                mContent.setText(R.string.sensor_privacy_mic_blocked_with_exception_dialog_content);
            } else {
                mContent.setText(R.string.sensor_privacy_mic_blocked_no_exception_dialog_content);
            }
            mIcon.setImageResource(R.drawable.unblock_hw_sensor_microphone);
            mSecondIcon.setVisibility(View.GONE);
        } else {
            mTitle.setText(R.string.sensor_privacy_mic_turned_on_dialog_title);
            mContent.setText(R.string.sensor_privacy_mic_unblocked_dialog_content);
            mIcon.setImageResource(com.android.internal.R.drawable.ic_mic_allowed);
            mSecondIcon.setVisibility(View.GONE);
        }
    }

    private void updateUiForCameraUpdate(boolean blocked) {
        if (blocked) {
            mTitle.setText(R.string.sensor_privacy_camera_turned_off_dialog_title);
            mContent.setText(R.string.sensor_privacy_camera_blocked_dialog_content);
            mIcon.setImageResource(R.drawable.unblock_hw_sensor_camera);
            mSecondIcon.setVisibility(View.GONE);
        } else {
            mTitle.setText(R.string.sensor_privacy_camera_turned_on_dialog_title);
            mContent.setText(R.string.sensor_privacy_camera_unblocked_dialog_content);
            mIcon.setImageResource(com.android.internal.R.drawable.ic_camera_allowed);
            mSecondIcon.setVisibility(View.GONE);
        }
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

    private boolean isExplicitUserInteractionAudioBypassAllowed() {
        return mGlobalSettings.getInt(
                Settings.Global.RECEIVE_EXPLICIT_USER_INTERACTION_AUDIO_ENABLED, 1) == 1;
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
