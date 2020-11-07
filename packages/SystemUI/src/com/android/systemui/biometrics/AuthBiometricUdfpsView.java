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
import android.graphics.Rect;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;

import com.android.systemui.R;

/**
 * Manages the layout for under-display fingerprint sensors (UDFPS). Ensures that UI elements
 * do not overlap with
 */
public class AuthBiometricUdfpsView extends AuthBiometricFingerprintView {

    private static final String TAG = "AuthBiometricUdfpsView";

    @Nullable private FingerprintSensorPropertiesInternal mSensorProps;

    public AuthBiometricUdfpsView(Context context) {
        this(context, null /* attrs */);
    }

    public AuthBiometricUdfpsView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    void setSensorProps(@NonNull FingerprintSensorPropertiesInternal prop) {
        mSensorProps = prop;
    }

    /**
     * For devices where the sensor is too high up, calculates the amount of padding necessary to
     * move/center the biometric icon within the sensor's physical location.
     */
    static int calculateBottomSpacerHeight(int displayHeightPx, int navbarHeightPx,
            int dialogBottomMarginPx, @NonNull View buttonBar, @NonNull View textIndicator,
            @NonNull FingerprintSensorPropertiesInternal sensorProperties) {
        final int sensorDistanceFromBottom = displayHeightPx - sensorProperties.sensorLocationY
                - sensorProperties.sensorRadius;

        final int spacerHeight = sensorDistanceFromBottom
                - textIndicator.getMeasuredHeight()
                - buttonBar.getMeasuredHeight()
                - dialogBottomMarginPx
                - navbarHeightPx;

        Log.d(TAG, "Display height: " + displayHeightPx
                + ", Distance from bottom: " + sensorDistanceFromBottom
                + ", Bottom margin: " + dialogBottomMarginPx
                + ", Navbar height: " + navbarHeightPx
                + ", Spacer height: " + spacerHeight);

        return spacerHeight;
    }

    @Override
    AuthDialog.LayoutParams onMeasureInternal(int width, int height) {
        final View spaceBelowIcon = findViewById(R.id.space_below_icon);
        spaceBelowIcon.setVisibility(View.VISIBLE);

        // Get the height of the everything below the icon. Currently, that's the indicator and
        // button bar
        final View textIndicator = findViewById(R.id.indicator);
        final View buttonBar = findViewById(R.id.button_bar);

        // Figure out where the bottom of the sensor anim should be.
        // Navbar + dialogMargin + buttonBar + textIndicator + spacerHeight = sensorDistFromBottom
        final int dialogBottomMarginPx = getResources()
                .getDimensionPixelSize(R.dimen.biometric_dialog_border_padding);
        final WindowManager wm = getContext().getSystemService(WindowManager.class);
        final Rect bounds = wm.getCurrentWindowMetrics().getBounds();
        final int navbarHeight = wm.getCurrentWindowMetrics().getWindowInsets()
                .getInsets(WindowInsets.Type.navigationBars()).toRect().height();
        final int displayHeight = bounds.height();

        final int spacerHeight = calculateBottomSpacerHeight(displayHeight, navbarHeight,
                dialogBottomMarginPx, buttonBar, textIndicator, mSensorProps);

        // Go through each of the children and do the custom measurement.
        int totalHeight = 0;
        final int numChildren = getChildCount();
        final int sensorDiameter = mSensorProps.sensorRadius * 2;
        for (int i = 0; i < numChildren; i++) {
            final View child = getChildAt(i);

            if (child.getId() == R.id.biometric_icon_frame) {
                // Create a frame that's exactly the size of the sensor circle
                child.measure(
                        MeasureSpec.makeMeasureSpec(sensorDiameter, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(sensorDiameter, MeasureSpec.EXACTLY));
            } else if (child.getId() == R.id.biometric_icon) {
                // Icon should never be larger than the circle
                child.measure(
                        MeasureSpec.makeMeasureSpec(sensorDiameter, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(sensorDiameter, MeasureSpec.AT_MOST));
            } else if (child.getId() == R.id.space_above_icon) {
                child.measure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(child.getLayoutParams().height,
                                MeasureSpec.EXACTLY));
            } else if (child.getId() == R.id.button_bar) {
                child.measure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(child.getLayoutParams().height,
                                MeasureSpec.EXACTLY));
            } else if (child.getId() == R.id.space_below_icon) {
                // Set the spacer height so the fingerprint icon is on the physical sensor area
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(spacerHeight, MeasureSpec.EXACTLY));
            } else {
                child.measure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
            }

            if (child.getVisibility() != View.GONE) {
                totalHeight += child.getMeasuredHeight();
            }
        }

        return new AuthDialog.LayoutParams(width, totalHeight);
    }
}
