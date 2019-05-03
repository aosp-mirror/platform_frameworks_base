/*
 * Copyright (C) 2016 The CyanogenMod Project
 *               2018 The LineageOS Project
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
package com.android.server.custom.display;

import android.animation.FloatArrayEvaluator;
import android.animation.ValueAnimator;
import android.animation.ValueAnimator.AnimatorUpdateListener;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.MathUtils;
import android.util.Slog;
import android.view.animation.LinearInterpolator;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;

import com.android.internal.custom.hardware.LineageHardwareManager;
import com.android.internal.custom.hardware.LiveDisplayManager;
import android.provider.Settings;

public class DisplayHardwareController extends LiveDisplayFeature {

    private final LineageHardwareManager mHardware;

    // hardware capabilities
    private final boolean mUseAutoContrast;
    private final boolean mUseColorAdjustment;
    private final boolean mUseColorEnhancement;
    private final boolean mUseCABC;
    private final boolean mUseReaderMode;
    private final boolean mUseDisplayModes;

    // default values
    private final boolean mDefaultAutoContrast;
    private final boolean mDefaultColorEnhancement;
    private final boolean mDefaultCABC;

    // color adjustment holders
    private final float[] mAdditionalAdjustment = getDefaultAdjustment();
    private final float[] mColorAdjustment = getDefaultAdjustment();

    private ValueAnimator mAnimator;

    private final int mMaxColor;

    // settings uris
    private static final Uri DISPLAY_AUTO_CONTRAST =
            Settings.System.getUriFor(Settings.System.DISPLAY_AUTO_CONTRAST);
    private static final Uri DISPLAY_COLOR_ADJUSTMENT =
            Settings.System.getUriFor(Settings.System.DISPLAY_COLOR_ADJUSTMENT);
    private static final Uri DISPLAY_COLOR_ENHANCE =
            Settings.System.getUriFor(Settings.System.DISPLAY_COLOR_ENHANCE);
    private static final Uri DISPLAY_CABC =
            Settings.System.getUriFor(Settings.System.DISPLAY_CABC);
    private static final Uri DISPLAY_READING_MODE =
            Settings.System.getUriFor(Settings.System.DISPLAY_READING_MODE);

    public DisplayHardwareController(Context context, Handler handler) {
        super(context, handler);

        mHardware = LineageHardwareManager.getInstance(mContext);
        mUseCABC = mHardware
                .isSupported(LineageHardwareManager.FEATURE_ADAPTIVE_BACKLIGHT);
        mDefaultCABC = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_defaultCABC);

        mUseColorEnhancement = mHardware
                .isSupported(LineageHardwareManager.FEATURE_COLOR_ENHANCEMENT);
        mDefaultColorEnhancement = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_defaultColorEnhancement);

        mUseAutoContrast = mHardware
                .isSupported(LineageHardwareManager.FEATURE_AUTO_CONTRAST);
        mDefaultAutoContrast = mContext.getResources().getBoolean(
                com.android.internal.R.bool.config_defaultAutoContrast);

        mUseColorAdjustment = mHardware
                .isSupported(LineageHardwareManager.FEATURE_DISPLAY_COLOR_CALIBRATION);

        mUseDisplayModes = mHardware
                .isSupported(LineageHardwareManager.FEATURE_DISPLAY_MODES);

        mUseReaderMode = mHardware
                .isSupported(LineageHardwareManager.FEATURE_READING_ENHANCEMENT);

        if (mUseColorAdjustment) {
            mMaxColor = mHardware.getDisplayColorCalibrationMax();
            copyColors(getColorAdjustment(), mColorAdjustment);
        } else {
            mMaxColor = 0;
        }
    }

    @Override
    public void onStart() {
        final ArrayList<Uri> settings = new ArrayList<Uri>();

        if (mUseCABC) {
            settings.add(DISPLAY_CABC);
        }
        if (mUseColorEnhancement) {
            settings.add(DISPLAY_COLOR_ENHANCE);
        }
        if (mUseAutoContrast) {
            settings.add(DISPLAY_AUTO_CONTRAST);
        }
        if (mUseColorAdjustment) {
            settings.add(DISPLAY_COLOR_ADJUSTMENT);
        }
        if (mUseReaderMode) {
            settings.add(DISPLAY_READING_MODE);
        }

        if (settings.size() == 0) {
            return;
        }

        registerSettings(settings.toArray(new Uri[0]));
    }

    @Override
    public boolean getCapabilities(final BitSet caps) {
        if (mUseAutoContrast) {
            caps.set(LiveDisplayManager.FEATURE_AUTO_CONTRAST);
        }
        if (mUseColorEnhancement) {
            caps.set(LiveDisplayManager.FEATURE_COLOR_ENHANCEMENT);
        }
        if (mUseCABC) {
            caps.set(LiveDisplayManager.FEATURE_CABC);
        }
        if (mUseColorAdjustment) {
            caps.set(LiveDisplayManager.FEATURE_COLOR_ADJUSTMENT);
        }
        if (mUseDisplayModes) {
            caps.set(LiveDisplayManager.FEATURE_DISPLAY_MODES);
        }
        if (mUseReaderMode) {
            caps.set(LiveDisplayManager.FEATURE_READING_ENHANCEMENT);
        }
        return mUseAutoContrast || mUseColorEnhancement || mUseCABC || mUseColorAdjustment ||
            mUseDisplayModes || mUseReaderMode;
    }

    @Override
    public synchronized void onSettingsChanged(Uri uri) {
        if (uri == null || uri.equals(DISPLAY_CABC)) {
            updateCABCMode();
        }
        if (uri == null || uri.equals(DISPLAY_AUTO_CONTRAST)) {
            updateAutoContrast();
        }
        if (uri == null || uri.equals(DISPLAY_COLOR_ENHANCE)) {
            updateColorEnhancement();
        }
        if (uri == null || uri.equals(DISPLAY_COLOR_ADJUSTMENT)) {
            copyColors(getColorAdjustment(), mColorAdjustment);
            updateColorAdjustment();
        }
    }

    private synchronized void updateHardware() {
        if (isScreenOn()) {
            updateCABCMode();
            updateAutoContrast();
            updateColorEnhancement();
        }
    }

    @Override
    protected void onUpdate() {
        updateHardware();
    }

    @Override
    protected synchronized void onScreenStateChanged() {
        if (mUseColorAdjustment) {
            if (mAnimator != null && mAnimator.isRunning() && !isScreenOn()) {
                mAnimator.cancel();
            } else if (isScreenOn()) {
                updateColorAdjustment();
            }
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println();
        pw.println("DisplayHardwareController Configuration:");
        pw.println("  mUseAutoContrast=" + mUseAutoContrast);
        pw.println("  mUseColorAdjustment=" + mUseColorAdjustment);
        pw.println("  mUseColorEnhancement="  + mUseColorEnhancement);
        pw.println("  mUseCABC=" + mUseCABC);
        pw.println("  mUseDisplayModes=" + mUseDisplayModes);
        pw.println("  mUseReaderMode=" + mUseReaderMode);
        pw.println();
        pw.println("  DisplayHardwareController State:");
        pw.println("    mAutoContrast=" + isAutoContrastEnabled());
        pw.println("    mColorEnhancement=" + isColorEnhancementEnabled());
        pw.println("    mCABC=" + isCABCEnabled());
        pw.println("    mColorAdjustment=" + Arrays.toString(mColorAdjustment));
        pw.println("    mAdditionalAdjustment=" + Arrays.toString(mAdditionalAdjustment));
        pw.println("    hardware setting=" + Arrays.toString(mHardware.getDisplayColorCalibration()));
    }

    /**
     * Automatic contrast optimization
     */
    private void updateAutoContrast() {
        if (!mUseAutoContrast) {
            return;
        }
        mHardware.set(LineageHardwareManager.FEATURE_AUTO_CONTRAST, isAutoContrastEnabled());
    }

    /**
     * Color enhancement is optional
     */
    private void updateColorEnhancement() {
        if (!mUseColorEnhancement) {
            return;
        }
        mHardware.set(LineageHardwareManager.FEATURE_COLOR_ENHANCEMENT,
                (!isLowPowerMode() || mDefaultColorEnhancement) && isColorEnhancementEnabled());
    }

    /**
     * Adaptive backlight / low power mode. Turn it off when under very bright light.
     */
    private void updateCABCMode() {
        if (!mUseCABC) {
            return;
        }
        mHardware.set(LineageHardwareManager.FEATURE_ADAPTIVE_BACKLIGHT, isCABCEnabled());
    }

    private synchronized void updateColorAdjustment() {
        if (!mUseColorAdjustment) {
            return;
        }

        final float[] rgb = getDefaultAdjustment();

        copyColors(mColorAdjustment, rgb);
        rgb[0] *= mAdditionalAdjustment[0];
        rgb[1] *= mAdditionalAdjustment[1];
        rgb[2] *= mAdditionalAdjustment[2];

        if (DEBUG) {
            Slog.d(TAG, "updateColorAdjustment: " + Arrays.toString(rgb));
        }

        if (validateColors(rgb)) {
            animateDisplayColor(rgb);
        }
    }

    /**
     * Smoothly animate the current display colors to the new value.
     */
    private synchronized void animateDisplayColor(float[] targetColors) {

        // always start with the current values in the hardware
        int[] currentInts = mHardware.getDisplayColorCalibration();
        float[] currentColors = new float[] {
                (float)currentInts[0] / (float)mMaxColor,
                (float)currentInts[1] / (float)mMaxColor,
                (float)currentInts[2] / (float)mMaxColor };

        if (currentColors[0] == targetColors[0] &&
                currentColors[1] == targetColors[1] &&
                currentColors[2] == targetColors[2]) {
            return;
        }

        // max 500 ms, scaled vs. the largest delta
        long duration = (long)(750 * (Math.max(Math.max(
                Math.abs(currentColors[0] - targetColors[0]),
                Math.abs(currentColors[1] - targetColors[1])),
                Math.abs(currentColors[2] - targetColors[2]))));

        if (DEBUG) {
            Slog.d(TAG, "animateDisplayColor current=" + Arrays.toString(currentColors) +
                    " targetColors=" + Arrays.toString(targetColors) + " duration=" + duration);
        }

        if (mAnimator != null) {
            mAnimator.cancel();
            mAnimator.removeAllUpdateListeners();
        }

        mAnimator = ValueAnimator.ofObject(
                new FloatArrayEvaluator(new float[3]), currentColors, targetColors);
        mAnimator.setDuration(duration);
        mAnimator.setInterpolator(new LinearInterpolator());
        mAnimator.addUpdateListener(new AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(final ValueAnimator animation) {
                synchronized (DisplayHardwareController.this) {
                    if (isScreenOn()) {
                        float[] value = (float[]) animation.getAnimatedValue();
                        mHardware.setDisplayColorCalibration(new int[] {
                                (int) (value[0] * mMaxColor),
                                (int) (value[1] * mMaxColor),
                                (int) (value[2] * mMaxColor)
                        });
                        screenRefresh();
                    }
                }
            }
        });
        mAnimator.start();
    }

    /**
     * Tell SurfaceFlinger to repaint the screen. This is called after updating
     * hardware registers for display calibration to have an immediate effect.
     */
    private void screenRefresh() {
        try {
            final IBinder flinger = ServiceManager.getService("SurfaceFlinger");
            if (flinger != null) {
                final Parcel data = Parcel.obtain();
                data.writeInterfaceToken("android.ui.ISurfaceComposer");
                flinger.transact(1004, data, null, 0);
                data.recycle();
            }
        } catch (RemoteException ex) {
            Slog.e(TAG, "Failed to refresh screen", ex);
        }
    }

    /**
     * Ensure all values are within range
     *
     * @param colors
     * @return true if valid
     */
    private boolean validateColors(float[] colors) {
        if (colors == null || colors.length != 3) {
            return false;
        }

        for (int i = 0; i < 3; i++) {
            colors[i] = MathUtils.constrain(colors[i], 0.0f, 1.0f);
        }
        return true;
    }

    /**
     * Parse and sanity check an RGB triplet from a string.
     */
    private boolean parseColorAdjustment(String rgbString, float[] dest) {
        String[] adj = rgbString == null ? null : rgbString.split(" ");

        if (adj == null || adj.length != 3 || dest == null || dest.length != 3) {
            return false;
        }

        try {
            dest[0] = Float.parseFloat(adj[0]);
            dest[1] = Float.parseFloat(adj[1]);
            dest[2] = Float.parseFloat(adj[2]);
        } catch (NumberFormatException e) {
            Slog.e(TAG, e.getMessage(), e);
            return false;
        }

        // sanity check
        return validateColors(dest);
    }

    /**
     * Additional adjustments provided by night mode
     *
     * @param adj
     */
    synchronized boolean setAdditionalAdjustment(float[] adj) {
        if (!mUseColorAdjustment) {
            return false;
        }

        if (DEBUG) {
            Slog.d(TAG, "setAdditionalAdjustment: " + Arrays.toString(adj));
        }

        // Sanity check this so we don't mangle the display
        if (validateColors(adj)) {
            copyColors(adj, mAdditionalAdjustment);
            updateColorAdjustment();
            return true;
        }
        return false;
    }

    boolean getDefaultCABC() {
        return mDefaultCABC;
    }

    boolean getDefaultAutoContrast() {
        return mDefaultAutoContrast;
    }

    boolean getDefaultColorEnhancement() {
        return mDefaultColorEnhancement;
    }

    boolean isAutoContrastEnabled() {
        return mUseAutoContrast &&
                getBoolean(Settings.System.DISPLAY_AUTO_CONTRAST, mDefaultAutoContrast);
    }

    boolean setAutoContrastEnabled(boolean enabled) {
        if (!mUseAutoContrast) {
            return false;
        }
        putBoolean(Settings.System.DISPLAY_AUTO_CONTRAST, enabled);
        return true;
    }

    boolean isCABCEnabled() {
        return mUseCABC &&
                getBoolean(Settings.System.DISPLAY_CABC, mDefaultCABC);
    }

    boolean setCABCEnabled(boolean enabled) {
        if (!mUseCABC) {
            return false;
        }
        putBoolean(Settings.System.DISPLAY_CABC, enabled);
        return true;
    }

    boolean isColorEnhancementEnabled() {
        return mUseColorEnhancement &&
                getBoolean(Settings.System.DISPLAY_COLOR_ENHANCE,
                mDefaultColorEnhancement);
    }

    boolean setColorEnhancementEnabled(boolean enabled) {
        if (!mUseColorEnhancement) {
            return false;
        }
        putBoolean(Settings.System.DISPLAY_COLOR_ENHANCE, enabled);
        return true;
    }

    float[] getColorAdjustment() {
        if (!mUseColorAdjustment) {
            return getDefaultAdjustment();
        }
        float[] cur = new float[3];
        if (!parseColorAdjustment(getString(Settings.System.DISPLAY_COLOR_ADJUSTMENT), cur)) {
            // clear it out if invalid
            cur = getDefaultAdjustment();
            saveColorAdjustmentString(cur);
        }
        return cur;
    }

    boolean setColorAdjustment(float[] adj) {
        // sanity check
        if (!mUseColorAdjustment || !validateColors(adj)) {
            return false;
        }
        saveColorAdjustmentString(adj);
        return true;
    }

    private void saveColorAdjustmentString(final float[] adj) {
        StringBuilder sb = new StringBuilder();
        sb.append(adj[0]).append(" ").append(adj[1]).append(" ").append(adj[2]);
        putString(Settings.System.DISPLAY_COLOR_ADJUSTMENT, sb.toString());
    }

    boolean hasColorAdjustment() {
        return mUseColorAdjustment;
    }

    private static float[] getDefaultAdjustment() {
        return new float[] { 1.0f, 1.0f, 1.0f };
    }

    private void copyColors(float[] src, float[] dst) {
        if (src != null && dst != null && src.length == 3 && dst.length == 3) {
            dst[0] = src[0];
            dst[1] = src[1];
            dst[2] = src[2];
        }
    }
}
