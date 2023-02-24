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

package com.android.systemui.biometrics;

import android.annotation.IdRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Insets;
import android.graphics.Rect;
import android.hardware.biometrics.SensorLocationInternal;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.os.Build;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.View.MeasureSpec;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.FrameLayout;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.R;

/**
 * Adapter that remeasures an auth dialog view to ensure that it matches the location of a physical
 * under-display fingerprint sensor (UDFPS).
 */
public class UdfpsDialogMeasureAdapter {
    private static final String TAG = "UdfpsDialogMeasurementAdapter";
    private static final boolean DEBUG = Build.IS_USERDEBUG || Build.IS_ENG;

    @NonNull private final ViewGroup mView;
    @NonNull private final FingerprintSensorPropertiesInternal mSensorProps;
    @Nullable private WindowManager mWindowManager;
    private int mBottomSpacerHeight;

    public UdfpsDialogMeasureAdapter(
            @NonNull ViewGroup view, @NonNull FingerprintSensorPropertiesInternal sensorProps) {
        mView = view;
        mSensorProps = sensorProps;
        mWindowManager = mView.getContext().getSystemService(WindowManager.class);
    }

    @NonNull
    FingerprintSensorPropertiesInternal getSensorProps() {
        return mSensorProps;
    }

    @NonNull
    AuthDialog.LayoutParams onMeasureInternal(
            int width, int height, @NonNull AuthDialog.LayoutParams layoutParams,
            float scaleFactor) {

        final int displayRotation = mView.getDisplay().getRotation();
        switch (displayRotation) {
            case Surface.ROTATION_0:
                return onMeasureInternalPortrait(width, height, scaleFactor);
            case Surface.ROTATION_90:
            case Surface.ROTATION_270:
                return onMeasureInternalLandscape(width, height, scaleFactor);
            default:
                Log.e(TAG, "Unsupported display rotation: " + displayRotation);
                return layoutParams;
        }
    }

    /**
     * @return the actual (and possibly negative) bottom spacer height. If negative, this indicates
     * that the UDFPS sensor is too low. Our current xml and custom measurement logic is very hard
     * too cleanly support this case. So, let's have the onLayout code translate the sensor location
     * instead.
     */
    int getBottomSpacerHeight() {
        return mBottomSpacerHeight;
    }

    /**
     * @return sensor diameter size as scaleFactor
     */
    public int getSensorDiameter(float scaleFactor) {
        return (int) (scaleFactor * mSensorProps.getLocation().sensorRadius * 2);
    }

    @NonNull
    private AuthDialog.LayoutParams onMeasureInternalPortrait(int width, int height,
            float scaleFactor) {
        final WindowMetrics windowMetrics = mWindowManager.getMaximumWindowMetrics();

        // Figure out where the bottom of the sensor anim should be.
        final int textIndicatorHeight = getViewHeightPx(R.id.indicator);
        final int buttonBarHeight = getViewHeightPx(R.id.button_bar);
        final int dialogMargin = getDialogMarginPx();
        final int displayHeight = getMaximumWindowBounds(windowMetrics).height();
        final Insets navbarInsets = getNavbarInsets(windowMetrics);
        mBottomSpacerHeight = calculateBottomSpacerHeightForPortrait(
                mSensorProps, displayHeight, textIndicatorHeight, buttonBarHeight,
                dialogMargin, navbarInsets.bottom, scaleFactor);

        // Go through each of the children and do the custom measurement.
        int totalHeight = 0;
        final int numChildren = mView.getChildCount();
        final int sensorDiameter = getSensorDiameter(scaleFactor);
        for (int i = 0; i < numChildren; i++) {
            final View child = mView.getChildAt(i);
            if (child.getId() == R.id.biometric_icon_frame) {
                final FrameLayout iconFrame = (FrameLayout) child;
                final View icon = iconFrame.getChildAt(0);
                // Create a frame that's exactly the height of the sensor circle.
                iconFrame.measure(
                        MeasureSpec.makeMeasureSpec(
                                child.getLayoutParams().width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(sensorDiameter, MeasureSpec.EXACTLY));

                // Ensure that the icon is never larger than the sensor.
                icon.measure(
                        MeasureSpec.makeMeasureSpec(sensorDiameter, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(sensorDiameter, MeasureSpec.AT_MOST));
            } else if (child.getId() == R.id.space_above_icon) {
                child.measure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(
                                child.getLayoutParams().height, MeasureSpec.EXACTLY));
            } else if (child.getId() == R.id.button_bar) {
                child.measure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(child.getLayoutParams().height,
                                MeasureSpec.EXACTLY));
            } else if (child.getId() == R.id.space_below_icon) {
                // Set the spacer height so the fingerprint icon is on the physical sensor area
                final int clampedSpacerHeight = Math.max(mBottomSpacerHeight, 0);
                child.measure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(clampedSpacerHeight, MeasureSpec.EXACTLY));
            } else if (child.getId() == R.id.description) {
                //skip description view and compute later
                continue;
            } else {
                child.measure(
                        MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
            }

            if (child.getVisibility() != View.GONE) {
                totalHeight += child.getMeasuredHeight();
            }
        }

        //re-calculate the height of description
        View description = mView.findViewById(R.id.description);
        if (description != null && description.getVisibility() != View.GONE) {
            totalHeight += measureDescription(description, displayHeight, width, totalHeight);
        }

        return new AuthDialog.LayoutParams(width, totalHeight);
    }

    private int measureDescription(View description, int displayHeight, int currWidth,
                                   int currHeight) {
        //description view should be measured in AuthBiometricFingerprintView#onMeasureInternal
        //so we could getMeasuredHeight in onMeasureInternalPortrait directly.
        int newHeight = description.getMeasuredHeight() + currHeight;
        int limit = (int) (displayHeight * 0.75);
        if (newHeight > limit) {
            description.measure(
                    MeasureSpec.makeMeasureSpec(currWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(limit - currHeight, MeasureSpec.EXACTLY));
        }
        return description.getMeasuredHeight();
    }

    @NonNull
    private AuthDialog.LayoutParams onMeasureInternalLandscape(int width, int height,
            float scaleFactor) {
        final WindowMetrics windowMetrics = mWindowManager.getMaximumWindowMetrics();

        // Find the spacer height needed to vertically align the icon with the sensor.
        final int titleHeight = getViewHeightPx(R.id.title);
        final int subtitleHeight = getViewHeightPx(R.id.subtitle);
        final int descriptionHeight = getViewHeightPx(R.id.description);
        final int topSpacerHeight = getViewHeightPx(R.id.space_above_icon);
        final int textIndicatorHeight = getViewHeightPx(R.id.indicator);
        final int buttonBarHeight = getViewHeightPx(R.id.button_bar);

        final Insets navbarInsets = getNavbarInsets(windowMetrics);
        final int bottomSpacerHeight = calculateBottomSpacerHeightForLandscape(titleHeight,
                subtitleHeight, descriptionHeight, topSpacerHeight, textIndicatorHeight,
                buttonBarHeight, navbarInsets.bottom);

        // Find the spacer width needed to horizontally align the icon with the sensor.
        final int displayWidth = getMaximumWindowBounds(windowMetrics).width();
        final int dialogMargin = getDialogMarginPx();
        final int horizontalInset = navbarInsets.left + navbarInsets.right;
        final int horizontalSpacerWidth = calculateHorizontalSpacerWidthForLandscape(
                mSensorProps, displayWidth, dialogMargin, horizontalInset, scaleFactor);

        final int sensorDiameter = getSensorDiameter(scaleFactor);
        final int remeasuredWidth = sensorDiameter + 2 * horizontalSpacerWidth;

        int remeasuredHeight = 0;
        final int numChildren = mView.getChildCount();
        for (int i = 0; i < numChildren; i++) {
            final View child = mView.getChildAt(i);
            if (child.getId() == R.id.biometric_icon_frame) {
                final FrameLayout iconFrame = (FrameLayout) child;
                final View icon = iconFrame.getChildAt(0);
                // Create a frame that's exactly the height of the sensor circle.
                iconFrame.measure(
                        MeasureSpec.makeMeasureSpec(remeasuredWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(sensorDiameter, MeasureSpec.EXACTLY));

                // Ensure that the icon is never larger than the sensor.
                icon.measure(
                        MeasureSpec.makeMeasureSpec(sensorDiameter, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(sensorDiameter, MeasureSpec.AT_MOST));
            } else if (child.getId() == R.id.space_above_icon) {
                // Adjust the width and height of the top spacer if necessary.
                final int newTopSpacerHeight = child.getLayoutParams().height
                        - Math.min(bottomSpacerHeight, 0);
                child.measure(
                        MeasureSpec.makeMeasureSpec(remeasuredWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(newTopSpacerHeight, MeasureSpec.EXACTLY));
            } else if (child.getId() == R.id.button_bar) {
                // Adjust the width of the button bar while preserving its height.
                child.measure(
                        MeasureSpec.makeMeasureSpec(remeasuredWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(
                                child.getLayoutParams().height, MeasureSpec.EXACTLY));
            } else if (child.getId() == R.id.space_below_icon) {
                // Adjust the bottom spacer height to align the fingerprint icon with the sensor.
                final int newBottomSpacerHeight = Math.max(bottomSpacerHeight, 0);
                child.measure(
                        MeasureSpec.makeMeasureSpec(remeasuredWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(newBottomSpacerHeight, MeasureSpec.EXACTLY));
            } else {
                // Use the remeasured width for all other child views.
                child.measure(
                        MeasureSpec.makeMeasureSpec(remeasuredWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST));
            }

            if (child.getVisibility() != View.GONE) {
                remeasuredHeight += child.getMeasuredHeight();
            }
        }

        return new AuthDialog.LayoutParams(remeasuredWidth, remeasuredHeight);
    }

    private int getViewHeightPx(@IdRes int viewId) {
        final View view = mView.findViewById(viewId);
        return view != null && view.getVisibility() != View.GONE ? view.getMeasuredHeight() : 0;
    }

    private int getDialogMarginPx() {
        return mView.getResources().getDimensionPixelSize(R.dimen.biometric_dialog_border_padding);
    }

    @NonNull
    private static Insets getNavbarInsets(@Nullable WindowMetrics windowMetrics) {
        return windowMetrics != null
                ? windowMetrics.getWindowInsets().getInsets(WindowInsets.Type.navigationBars())
                : Insets.NONE;
    }

    @NonNull
    private static Rect getMaximumWindowBounds(@Nullable WindowMetrics windowMetrics) {
        return windowMetrics != null ? windowMetrics.getBounds() : new Rect();
    }

    /**
     * For devices in portrait orientation where the sensor is too high up, calculates the amount of
     * padding necessary to center the biometric icon within the sensor's physical location.
     */
    @VisibleForTesting
    static int calculateBottomSpacerHeightForPortrait(
            @NonNull FingerprintSensorPropertiesInternal sensorProperties, int displayHeightPx,
            int textIndicatorHeightPx, int buttonBarHeightPx, int dialogMarginPx,
            int navbarBottomInsetPx, float scaleFactor) {
        final SensorLocationInternal location = sensorProperties.getLocation();
        final int sensorDistanceFromBottom = displayHeightPx
                - (int) (scaleFactor * location.sensorLocationY)
                - (int) (scaleFactor * location.sensorRadius);

        final int spacerHeight = sensorDistanceFromBottom
                - textIndicatorHeightPx
                - buttonBarHeightPx
                - dialogMarginPx
                - navbarBottomInsetPx;

        if (DEBUG) {
            Log.d(TAG, "Display height: " + displayHeightPx
                    + ", Distance from bottom: " + sensorDistanceFromBottom
                    + ", Bottom margin: " + dialogMarginPx
                    + ", Navbar bottom inset: " + navbarBottomInsetPx
                    + ", Bottom spacer height (portrait): " + spacerHeight
                    + ", Scale Factor: " + scaleFactor);
        }

        return spacerHeight;
    }

    /**
     * For devices in landscape orientation where the sensor is too high up, calculates the amount
     * of padding necessary to center the biometric icon within the sensor's physical location.
     */
    @VisibleForTesting
    static int calculateBottomSpacerHeightForLandscape(int titleHeightPx, int subtitleHeightPx,
            int descriptionHeightPx, int topSpacerHeightPx, int textIndicatorHeightPx,
            int buttonBarHeightPx, int navbarBottomInsetPx) {

        final int dialogHeightAboveIcon = titleHeightPx
                + subtitleHeightPx
                + descriptionHeightPx
                + topSpacerHeightPx;

        final int dialogHeightBelowIcon = textIndicatorHeightPx + buttonBarHeightPx;

        final int bottomSpacerHeight = dialogHeightAboveIcon
                - dialogHeightBelowIcon
                - navbarBottomInsetPx;

        if (DEBUG) {
            Log.d(TAG, "Title height: " + titleHeightPx
                    + ", Subtitle height: " + subtitleHeightPx
                    + ", Description height: " + descriptionHeightPx
                    + ", Top spacer height: " + topSpacerHeightPx
                    + ", Text indicator height: " + textIndicatorHeightPx
                    + ", Button bar height: " + buttonBarHeightPx
                    + ", Navbar bottom inset: " + navbarBottomInsetPx
                    + ", Bottom spacer height (landscape): " + bottomSpacerHeight);
        }

        return bottomSpacerHeight;
    }

    /**
     * For devices in landscape orientation where the sensor is too left/right, calculates the
     * amount of padding necessary to center the biometric icon within the sensor's physical
     * location.
     */
    @VisibleForTesting
    static int calculateHorizontalSpacerWidthForLandscape(
            @NonNull FingerprintSensorPropertiesInternal sensorProperties, int displayWidthPx,
            int dialogMarginPx, int navbarHorizontalInsetPx, float scaleFactor) {
        final SensorLocationInternal location = sensorProperties.getLocation();
        final int sensorDistanceFromEdge = displayWidthPx
                - (int) (scaleFactor * location.sensorLocationY)
                - (int) (scaleFactor * location.sensorRadius);

        final int horizontalPadding = sensorDistanceFromEdge
                - dialogMarginPx
                - navbarHorizontalInsetPx;

        if (DEBUG) {
            Log.d(TAG, "Display width: " + displayWidthPx
                    + ", Distance from edge: " + sensorDistanceFromEdge
                    + ", Dialog margin: " + dialogMarginPx
                    + ", Navbar horizontal inset: " + navbarHorizontalInsetPx
                    + ", Horizontal spacer width (landscape): " + horizontalPadding
                    + ", Scale Factor: " + scaleFactor);
        }

        return horizontalPadding;
    }
}
