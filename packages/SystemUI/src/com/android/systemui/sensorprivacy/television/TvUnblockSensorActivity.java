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

import android.hardware.SensorPrivacyManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.policy.IndividualSensorPrivacyController;
import com.android.systemui.tv.TvBottomSheetActivity;

import javax.inject.Inject;

/**
 * Bottom sheet that is shown when the camera/mic sensors are blocked by the global toggle and
 * allows the user to re-enable them.
 */
public class TvUnblockSensorActivity extends TvBottomSheetActivity {

    private static final String TAG = TvUnblockSensorActivity.class.getSimpleName();

    private static final int ALL_SENSORS = Integer.MAX_VALUE;
    private int mSensor = -1;

    private final IndividualSensorPrivacyController mSensorPrivacyController;
    private IndividualSensorPrivacyController.Callback mSensorPrivacyCallback;

    @Inject
    public TvUnblockSensorActivity(
            IndividualSensorPrivacyController individualSensorPrivacyController) {
        mSensorPrivacyController = individualSensorPrivacyController;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
            if (mSensor == ALL_SENSORS) {
                if (!mSensorPrivacyController.isSensorBlocked(CAMERA)
                        && !mSensorPrivacyController.isSensorBlocked(MICROPHONE)) {
                    finish();
                }
            } else if (this.mSensor == sensor && !blocked) {
                finish();
            }
        };

        initUI();
    }

    private void initUI() {
        TextView title = findViewById(R.id.bottom_sheet_title);
        TextView content = findViewById(R.id.bottom_sheet_body);
        ImageView icon = findViewById(R.id.bottom_sheet_icon);
        // mic icon if both icons are shown
        ImageView secondIcon = findViewById(R.id.bottom_sheet_second_icon);
        Button unblockButton = findViewById(R.id.bottom_sheet_positive_button);
        Button cancelButton = findViewById(R.id.bottom_sheet_negative_button);

        switch (mSensor) {
            case MICROPHONE:
                title.setText(R.string.sensor_privacy_start_use_mic_dialog_title);
                content.setText(R.string.sensor_privacy_start_use_mic_dialog_content);
                icon.setImageResource(com.android.internal.R.drawable.perm_group_microphone);
                secondIcon.setVisibility(View.GONE);
                break;
            case CAMERA:
                title.setText(R.string.sensor_privacy_start_use_camera_dialog_title);
                content.setText(R.string.sensor_privacy_start_use_camera_dialog_content);
                icon.setImageResource(com.android.internal.R.drawable.perm_group_camera);
                secondIcon.setVisibility(View.GONE);
                break;
            case ALL_SENSORS:
            default:
                title.setText(R.string.sensor_privacy_start_use_mic_camera_dialog_title);
                content.setText(R.string.sensor_privacy_start_use_mic_camera_dialog_content);
                icon.setImageResource(com.android.internal.R.drawable.perm_group_camera);
                secondIcon.setImageResource(com.android.internal.R.drawable.perm_group_microphone);
                break;
        }
        unblockButton.setText(
                com.android.internal.R.string.sensor_privacy_start_use_dialog_turn_on_button);
        unblockButton.setOnClickListener(v -> {
            if (mSensor == ALL_SENSORS) {
                mSensorPrivacyController.setSensorBlocked(OTHER, CAMERA, false);
                mSensorPrivacyController.setSensorBlocked(OTHER, MICROPHONE, false);
            } else {
                mSensorPrivacyController.setSensorBlocked(OTHER, mSensor, false);
            }
        });

        cancelButton.setText(android.R.string.cancel);
        cancelButton.setOnClickListener(v -> finish());
    }

    @Override
    public void onResume() {
        super.onResume();
        mSensorPrivacyController.addCallback(mSensorPrivacyCallback);
    }

    @Override
    public void onPause() {
        mSensorPrivacyController.removeCallback(mSensorPrivacyCallback);
        super.onPause();
    }

}
