/*
 * Copyright (C) 2015-2016 The CyanogenMod Project
 *               2017-2019 The LineageOS Project
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
package com.android.server.custom;

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.Range;

import com.android.server.display.color.DisplayTransformManager;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import com.android.internal.custom.hardware.ILineageHardwareService;
import com.android.internal.custom.hardware.LineageHardwareManager;
import com.android.internal.custom.hardware.DisplayMode;
import com.android.internal.custom.hardware.HSIC;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.android.internal.custom.app.LineageContextConstants;
import com.android.internal.custom.hardware.AdaptiveBacklight;
import com.android.internal.custom.hardware.AutoContrast;
import com.android.internal.custom.hardware.ColorBalance;
import com.android.internal.custom.hardware.ColorEnhancement;
import com.android.internal.custom.hardware.DisplayColorCalibration;
import com.android.internal.custom.hardware.DisplayModeControl;
import com.android.internal.custom.hardware.PictureAdjustment;
import com.android.internal.custom.hardware.ReadingEnhancement;
import com.android.internal.custom.hardware.SunlightEnhancement;

import static com.android.server.display.color.DisplayTransformManager.LEVEL_COLOR_MATRIX_NIGHT_DISPLAY;
import static com.android.server.display.color.DisplayTransformManager.LEVEL_COLOR_MATRIX_GRAYSCALE;

/** @hide */
public class LineageHardwareService extends SystemService {

    private static final boolean DEBUG = true;
    private static final String TAG = LineageHardwareService.class.getSimpleName();

    private final Context mContext;
    private final LineageHardwareInterface mLineageHwImpl;

    private interface LineageHardwareInterface {
        public int getSupportedFeatures();
        public boolean get(int feature);
        public boolean set(int feature, boolean enable);

        public int[] getDisplayColorCalibration();
        public boolean setDisplayColorCalibration(int[] rgb);

        public boolean requireAdaptiveBacklightForSunlightEnhancement();
        public boolean isSunlightEnhancementSelfManaged();

        public DisplayMode[] getDisplayModes();
        public DisplayMode getCurrentDisplayMode();
        public DisplayMode getDefaultDisplayMode();
        public boolean setDisplayMode(DisplayMode mode, boolean makeDefault);

        public int getColorBalanceMin();
        public int getColorBalanceMax();
        public int getColorBalance();
        public boolean setColorBalance(int value);

        public HSIC getPictureAdjustment();
        public HSIC getDefaultPictureAdjustment();
        public boolean setPictureAdjustment(HSIC hsic);
        public List<Range<Float>> getPictureAdjustmentRanges();
    }

    private class LegacyLineageHardware implements LineageHardwareInterface {

        private final int MIN = 0;
        private final int MAX = 255;

        /**
         * Matrix and offset used for converting color to grayscale.
         * Copied from com.android.server.accessibility.DisplayAdjustmentUtils.MATRIX_GRAYSCALE
         */
        private final float[] MATRIX_GRAYSCALE = {
            .2126f, .2126f, .2126f, 0,
            .7152f, .7152f, .7152f, 0,
            .0722f, .0722f, .0722f, 0,
                 0,      0,      0, 1
        };

        /** Full color matrix and offset */
        private final float[] MATRIX_NORMAL = {
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
        };

        private final int LEVEL_COLOR_MATRIX_CALIB = LEVEL_COLOR_MATRIX_NIGHT_DISPLAY + 1;
        private final int LEVEL_COLOR_MATRIX_READING = LEVEL_COLOR_MATRIX_GRAYSCALE + 1;

        private boolean mAcceleratedTransform;
        private DisplayTransformManager mDTMService;

        private int[] mCurColors = { MAX, MAX, MAX };
        private boolean mReadingEnhancementEnabled;

        private int mSupportedFeatures = 0;

        public LegacyLineageHardware() {
            mAcceleratedTransform = mContext.getResources().getBoolean(
                    com.android.internal.R.bool.config_setColorTransformAccelerated);
            if (AdaptiveBacklight.isSupported())
                mSupportedFeatures |= LineageHardwareManager.FEATURE_ADAPTIVE_BACKLIGHT;
            if (ColorEnhancement.isSupported())
                mSupportedFeatures |= LineageHardwareManager.FEATURE_COLOR_ENHANCEMENT;
            if (DisplayColorCalibration.isSupported())
                mSupportedFeatures |= LineageHardwareManager.FEATURE_DISPLAY_COLOR_CALIBRATION;
            if (ReadingEnhancement.isSupported())
                mSupportedFeatures |= LineageHardwareManager.FEATURE_READING_ENHANCEMENT;
            if (SunlightEnhancement.isSupported())
                mSupportedFeatures |= LineageHardwareManager.FEATURE_SUNLIGHT_ENHANCEMENT;
            if (AutoContrast.isSupported())
                mSupportedFeatures |= LineageHardwareManager.FEATURE_AUTO_CONTRAST;
            if (DisplayModeControl.isSupported())
                mSupportedFeatures |= LineageHardwareManager.FEATURE_DISPLAY_MODES;
            if (ColorBalance.isSupported())
                mSupportedFeatures |= LineageHardwareManager.FEATURE_COLOR_BALANCE;
            if (PictureAdjustment.isSupported())
                mSupportedFeatures |= LineageHardwareManager.FEATURE_PICTURE_ADJUSTMENT;
            if (mAcceleratedTransform) {
                mDTMService = LocalServices.getService(DisplayTransformManager.class);
                mSupportedFeatures |= LineageHardwareManager.FEATURE_DISPLAY_COLOR_CALIBRATION;
                mSupportedFeatures |= LineageHardwareManager.FEATURE_READING_ENHANCEMENT;
            }
        }

        public int getSupportedFeatures() {
            return mSupportedFeatures;
        }

        public boolean get(int feature) {
            switch(feature) {
                case LineageHardwareManager.FEATURE_ADAPTIVE_BACKLIGHT:
                    return AdaptiveBacklight.isEnabled();
                case LineageHardwareManager.FEATURE_AUTO_CONTRAST:
                    return AutoContrast.isEnabled();
                case LineageHardwareManager.FEATURE_COLOR_ENHANCEMENT:
                    return ColorEnhancement.isEnabled();
                case LineageHardwareManager.FEATURE_READING_ENHANCEMENT:
                    if (mAcceleratedTransform)
                        return mReadingEnhancementEnabled;
                    return ReadingEnhancement.isEnabled();
                case LineageHardwareManager.FEATURE_SUNLIGHT_ENHANCEMENT:
                    return SunlightEnhancement.isEnabled();
                default:
                    Log.e(TAG, "feature " + feature + " is not a boolean feature");
                    return false;
            }
        }

        public boolean set(int feature, boolean enable) {
            switch(feature) {
                case LineageHardwareManager.FEATURE_ADAPTIVE_BACKLIGHT:
                    return AdaptiveBacklight.setEnabled(enable);
                case LineageHardwareManager.FEATURE_AUTO_CONTRAST:
                    return AutoContrast.setEnabled(enable);
                case LineageHardwareManager.FEATURE_COLOR_ENHANCEMENT:
                    return ColorEnhancement.setEnabled(enable);
                case LineageHardwareManager.FEATURE_READING_ENHANCEMENT:
                    if (mAcceleratedTransform) {
                        mReadingEnhancementEnabled = enable;
                        mDTMService.setColorMatrix(LEVEL_COLOR_MATRIX_READING,
                                enable ? MATRIX_GRAYSCALE : MATRIX_NORMAL);
                        return true;
                    }
                    return ReadingEnhancement.setEnabled(enable);
                case LineageHardwareManager.FEATURE_SUNLIGHT_ENHANCEMENT:
                    return SunlightEnhancement.setEnabled(enable);
                default:
                    Log.e(TAG, "feature " + feature + " is not a boolean feature");
                    return false;
            }
        }

        private int[] splitStringToInt(String input, String delimiter) {
            if (input == null || delimiter == null) {
                return null;
            }
            String strArray[] = input.split(delimiter);
            try {
                int intArray[] = new int[strArray.length];
                for(int i = 0; i < strArray.length; i++) {
                    intArray[i] = Integer.parseInt(strArray[i]);
                }
                return intArray;
            } catch (NumberFormatException e) {
                /* ignore */
            }
            return null;
        }

        private String rgbToString(int[] rgb) {
            StringBuilder builder = new StringBuilder();
            builder.append(rgb[LineageHardwareManager.COLOR_CALIBRATION_RED_INDEX]);
            builder.append(" ");
            builder.append(rgb[LineageHardwareManager.COLOR_CALIBRATION_GREEN_INDEX]);
            builder.append(" ");
            builder.append(rgb[LineageHardwareManager.COLOR_CALIBRATION_BLUE_INDEX]);
            return builder.toString();
        }

        private float[] rgbToMatrix(int[] rgb) {
            float[] mat = new float[16];

            for (int i = 0; i < 3; i++) {
                // Sanity check
                if (rgb[i] > MAX)
                    rgb[i] = MAX;
                else if (rgb[i] < MIN)
                    rgb[i] = MIN;

                mat[i * 5] = (float)rgb[i] / (float)MAX;
            }

            mat[15] = 1.0f;
            return mat;
        }

        public int[] getDisplayColorCalibration() {
            int[] rgb = mAcceleratedTransform ? mCurColors :
                    splitStringToInt(DisplayColorCalibration.getCurColors(), " ");
            if (rgb == null || rgb.length != 3) {
                Log.e(TAG, "Invalid color calibration string");
                return null;
            }
            int[] currentCalibration = new int[5];
            currentCalibration[LineageHardwareManager.COLOR_CALIBRATION_RED_INDEX] = rgb[0];
            currentCalibration[LineageHardwareManager.COLOR_CALIBRATION_GREEN_INDEX] = rgb[1];
            currentCalibration[LineageHardwareManager.COLOR_CALIBRATION_BLUE_INDEX] = rgb[2];
            currentCalibration[LineageHardwareManager.COLOR_CALIBRATION_MIN_INDEX] =
                mAcceleratedTransform ? MIN : DisplayColorCalibration.getMinValue();
            currentCalibration[LineageHardwareManager.COLOR_CALIBRATION_MAX_INDEX] =
                mAcceleratedTransform ? MAX : DisplayColorCalibration.getMaxValue();
            return currentCalibration;
        }

        public boolean setDisplayColorCalibration(int[] rgb) {
            if (mAcceleratedTransform) {
                mCurColors = rgb;
                mDTMService.setColorMatrix(LEVEL_COLOR_MATRIX_CALIB, rgbToMatrix(rgb));
                return true;
            }
            return DisplayColorCalibration.setColors(rgbToString(rgb));
        }

        public boolean requireAdaptiveBacklightForSunlightEnhancement() {
            return SunlightEnhancement.isAdaptiveBacklightRequired();
        }

        public boolean isSunlightEnhancementSelfManaged() {
            return SunlightEnhancement.isSelfManaged();
        }

        public DisplayMode[] getDisplayModes() {
            return DisplayModeControl.getAvailableModes();
        }

        public DisplayMode getCurrentDisplayMode() {
            return DisplayModeControl.getCurrentMode();
        }

        public DisplayMode getDefaultDisplayMode() {
            return DisplayModeControl.getDefaultMode();
        }

        public boolean setDisplayMode(DisplayMode mode, boolean makeDefault) {
            return DisplayModeControl.setMode(mode, makeDefault);
        }

        public int getColorBalanceMin() {
            return ColorBalance.getMinValue();
        }

        public int getColorBalanceMax() {
            return ColorBalance.getMaxValue();
        }

        public int getColorBalance() {
            return ColorBalance.getValue();
        }

        public boolean setColorBalance(int value) {
            return ColorBalance.setValue(value);
        }

        public HSIC getPictureAdjustment() { return PictureAdjustment.getHSIC(); }

        public HSIC getDefaultPictureAdjustment() { return PictureAdjustment.getDefaultHSIC(); }

        public boolean setPictureAdjustment(HSIC hsic) { return PictureAdjustment.setHSIC(hsic); }

        public List<Range<Float>> getPictureAdjustmentRanges() {
            return Arrays.asList(
                    PictureAdjustment.getHueRange(),
                    PictureAdjustment.getSaturationRange(),
                    PictureAdjustment.getIntensityRange(),
                    PictureAdjustment.getContrastRange(),
                    PictureAdjustment.getSaturationThresholdRange());
        }
    }

    private LineageHardwareInterface getImpl(Context context) {
        return new LegacyLineageHardware();
    }

    public LineageHardwareService(Context context) {
        super(context);
        mContext = context;
        mLineageHwImpl = getImpl(context);
        publishBinderService(LineageContextConstants.LINEAGE_HARDWARE_SERVICE, mService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            Intent intent = new Intent("lineageos.intent.action.INITIALIZE_LINEAGE_HARDWARE");
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS");
        }
    }

    @Override
    public void onStart() {
    }

    private final IBinder mService = new ILineageHardwareService.Stub() {

        private boolean isSupported(int feature) {
            return (getSupportedFeatures() & feature) == feature;
        }

        @Override
        public int getSupportedFeatures() {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS", null);
            return mLineageHwImpl.getSupportedFeatures();
        }

        @Override
        public boolean get(int feature) {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS", null);
            if (!isSupported(feature)) {
                Log.e(TAG, "feature " + feature + " is not supported");
                return false;
            }
            return mLineageHwImpl.get(feature);
        }

        @Override
        public boolean set(int feature, boolean enable) {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS", null);
            if (!isSupported(feature)) {
                Log.e(TAG, "feature " + feature + " is not supported");
                return false;
            }
            return mLineageHwImpl.set(feature, enable);
        }

        @Override
        public int[] getDisplayColorCalibration() {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS", null);
            if (!isSupported(LineageHardwareManager.FEATURE_DISPLAY_COLOR_CALIBRATION)) {
                Log.e(TAG, "Display color calibration is not supported");
                return null;
            }
            return mLineageHwImpl.getDisplayColorCalibration();
        }

        @Override
        public boolean setDisplayColorCalibration(int[] rgb) {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS", null);
            if (!isSupported(LineageHardwareManager.FEATURE_DISPLAY_COLOR_CALIBRATION)) {
                Log.e(TAG, "Display color calibration is not supported");
                return false;
            }
            if (rgb.length < 3) {
                Log.e(TAG, "Invalid color calibration");
                return false;
            }
            return mLineageHwImpl.setDisplayColorCalibration(rgb);
        }

        @Override
        public boolean requireAdaptiveBacklightForSunlightEnhancement() {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS", null);
            if (!isSupported(LineageHardwareManager.FEATURE_SUNLIGHT_ENHANCEMENT)) {
                Log.e(TAG, "Sunlight enhancement is not supported");
                return false;
            }
            return mLineageHwImpl.requireAdaptiveBacklightForSunlightEnhancement();
        }

        @Override
        public boolean isSunlightEnhancementSelfManaged() {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS", null);
            if (!isSupported(LineageHardwareManager.FEATURE_SUNLIGHT_ENHANCEMENT)) {
                Log.e(TAG, "Sunlight enhancement is not supported");
                return false;
            }
            return mLineageHwImpl.isSunlightEnhancementSelfManaged();
        }

        @Override
        public DisplayMode[] getDisplayModes() {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS", null);
            if (!isSupported(LineageHardwareManager.FEATURE_DISPLAY_MODES)) {
                Log.e(TAG, "Display modes are not supported");
                return null;
            }
            return mLineageHwImpl.getDisplayModes();
        }

        @Override
        public DisplayMode getCurrentDisplayMode() {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS", null);
            if (!isSupported(LineageHardwareManager.FEATURE_DISPLAY_MODES)) {
                Log.e(TAG, "Display modes are not supported");
                return null;
            }
            return mLineageHwImpl.getCurrentDisplayMode();
        }

        @Override
        public DisplayMode getDefaultDisplayMode() {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS", null);
            if (!isSupported(LineageHardwareManager.FEATURE_DISPLAY_MODES)) {
                Log.e(TAG, "Display modes are not supported");
                return null;
            }
            return mLineageHwImpl.getDefaultDisplayMode();
        }

        @Override
        public boolean setDisplayMode(DisplayMode mode, boolean makeDefault) {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS", null);
            if (!isSupported(LineageHardwareManager.FEATURE_DISPLAY_MODES)) {
                Log.e(TAG, "Display modes are not supported");
                return false;
            }
            return mLineageHwImpl.setDisplayMode(mode, makeDefault);
        }

        @Override
        public int getColorBalanceMin() {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS", null);
            if (isSupported(LineageHardwareManager.FEATURE_COLOR_BALANCE)) {
                return mLineageHwImpl.getColorBalanceMin();
            }
            return 0;
        }

        @Override
        public int getColorBalanceMax() {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS", null);
            if (isSupported(LineageHardwareManager.FEATURE_COLOR_BALANCE)) {
                return mLineageHwImpl.getColorBalanceMax();
            }
            return 0;
        }

        @Override
        public int getColorBalance() {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS", null);
            if (isSupported(LineageHardwareManager.FEATURE_COLOR_BALANCE)) {
                return mLineageHwImpl.getColorBalance();
            }
            return 0;
        }

        @Override
        public boolean setColorBalance(int value) {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS", null);
            if (isSupported(LineageHardwareManager.FEATURE_COLOR_BALANCE)) {
                return mLineageHwImpl.setColorBalance(value);
            }
            return false;
        }

        @Override
        public HSIC getPictureAdjustment() {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS", null);
            if (isSupported(LineageHardwareManager.FEATURE_PICTURE_ADJUSTMENT)) {
                return mLineageHwImpl.getPictureAdjustment();
            }
            return new HSIC(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        }

        @Override
        public HSIC getDefaultPictureAdjustment() {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS", null);
            if (isSupported(LineageHardwareManager.FEATURE_PICTURE_ADJUSTMENT)) {
                return mLineageHwImpl.getDefaultPictureAdjustment();
            }
            return new HSIC(0.0f, 0.0f, 0.0f, 0.0f, 0.0f);
        }

        @Override
        public boolean setPictureAdjustment(HSIC hsic) {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS", null);
            if (isSupported(LineageHardwareManager.FEATURE_PICTURE_ADJUSTMENT) && hsic != null) {
                return mLineageHwImpl.setPictureAdjustment(hsic);
            }
            return false;
        }

        @Override
        public float[] getPictureAdjustmentRanges() {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS", null);
            if (isSupported(LineageHardwareManager.FEATURE_PICTURE_ADJUSTMENT)) {
                final List<Range<Float>> r = mLineageHwImpl.getPictureAdjustmentRanges();
                return new float[] {
                        r.get(0).getLower(), r.get(0).getUpper(),
                        r.get(1).getLower(), r.get(1).getUpper(),
                        r.get(2).getLower(), r.get(2).getUpper(),
                        r.get(3).getLower(), r.get(3).getUpper(),
                        r.get(4).getUpper(), r.get(4).getUpper() };
            }
            return new float[10];
        }
    };
}
