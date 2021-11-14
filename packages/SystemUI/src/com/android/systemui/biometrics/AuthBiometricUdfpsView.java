/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.biometrics;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.android.systemui.R;

/**
 * Manages the layout for under-display fingerprint sensors (UDFPS). Ensures that UI elements
 * do not overlap with
 */
public class AuthBiometricUdfpsView extends AuthBiometricFingerprintView {
    private static final String TAG = "AuthBiometricUdfpsView";

    @Nullable private UdfpsDialogMeasureAdapter mMeasureAdapter;

    public AuthBiometricUdfpsView(Context context) {
        this(context, null /* attrs */);
    }

    public AuthBiometricUdfpsView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setSensorProps(@NonNull FingerprintSensorPropertiesInternal sensorProps) {
        if (mMeasureAdapter == null || mMeasureAdapter.getSensorProps() != sensorProps) {
            mMeasureAdapter = new UdfpsDialogMeasureAdapter(this, sensorProps);
        }
    }

    @Override
    @NonNull
    AuthDialog.LayoutParams onMeasureInternal(int width, int height) {
        final AuthDialog.LayoutParams layoutParams = super.onMeasureInternal(width, height);
        return mMeasureAdapter != null
                ? mMeasureAdapter.onMeasureInternal(width, height, layoutParams)
                : layoutParams;
    }

    @Override
    void onLayoutInternal() {
        super.onLayoutInternal();

        // Move the UDFPS icon and indicator text if necessary. This probably only needs to happen
        // for devices where the UDFPS sensor is too low.
        // TODO(b/201510778): Update this logic to support cases where the sensor or text overlap
        //  the button bar area.
        final int bottomSpacerHeight = mMeasureAdapter.getBottomSpacerHeight();
        Log.w(TAG, "bottomSpacerHeight: " + bottomSpacerHeight);
        if (bottomSpacerHeight < 0) {
            FrameLayout iconFrame = findViewById(R.id.biometric_icon_frame);
            iconFrame.setTranslationY(-bottomSpacerHeight);

            TextView indicator = findViewById(R.id.indicator);
            indicator.setTranslationY(-bottomSpacerHeight);
        }
    }
}
