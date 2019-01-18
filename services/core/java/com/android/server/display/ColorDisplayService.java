/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.display;

import static com.android.server.display.DisplayTransformManager.LEVEL_COLOR_MATRIX_DISPLAY_WHITE_BALANCE;
import static com.android.server.display.DisplayTransformManager.LEVEL_COLOR_MATRIX_NIGHT_DISPLAY;
import static com.android.server.display.DisplayTransformManager.LEVEL_COLOR_MATRIX_SATURATION;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TypeEvaluator;
import android.animation.ValueAnimator;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Size;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.ColorSpace;
import android.hardware.display.ColorDisplayManager;
import android.hardware.display.IColorDisplayManager;
import android.net.Uri;
import android.opengl.Matrix;
import android.os.Binder;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.UserHandle;
import android.provider.Settings.Secure;
import android.provider.Settings.System;
import android.util.MathUtils;
import android.util.Slog;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.AnimationUtils;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.app.ColorDisplayController;
import com.android.internal.util.DumpUtils;
import com.android.server.DisplayThread;
import com.android.server.SystemService;
import com.android.server.twilight.TwilightListener;
import com.android.server.twilight.TwilightManager;
import com.android.server.twilight.TwilightState;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;

/**
 * Controls the display's color transforms.
 */
public final class ColorDisplayService extends SystemService {

    private static final String TAG = "ColorDisplayService";

    /**
     * The transition time, in milliseconds, for Night Display to turn on/off.
     */
    private static final long TRANSITION_DURATION = 3000L;

    /**
     * The identity matrix, used if one of the given matrices is {@code null}.
     */
    private static final float[] MATRIX_IDENTITY = new float[16];

    static {
        Matrix.setIdentityM(MATRIX_IDENTITY, 0);
    }

    private static final int MSG_APPLY_NIGHT_DISPLAY_IMMEDIATE = 0;
    private static final int MSG_APPLY_NIGHT_DISPLAY_ANIMATED = 1;
    private static final int MSG_APPLY_GLOBAL_SATURATION = 2;

    /**
     * Evaluator used to animate color matrix transitions.
     */
    private static final ColorMatrixEvaluator COLOR_MATRIX_EVALUATOR = new ColorMatrixEvaluator();

    private final TintController mNightDisplayTintController = new TintController() {

        private float[] mMatrixNightDisplay = new float[16];
        private final float[] mColorTempCoefficients = new float[9];

        /**
         * Set coefficients based on whether the color matrix is linear or not.
         */
        @Override
        public void setUp(Context context, boolean needsLinear) {
            final String[] coefficients = context.getResources().getStringArray(needsLinear
                    ? R.array.config_nightDisplayColorTemperatureCoefficients
                    : R.array.config_nightDisplayColorTemperatureCoefficientsNative);
            for (int i = 0; i < 9 && i < coefficients.length; i++) {
                mColorTempCoefficients[i] = Float.parseFloat(coefficients[i]);
            }
        }

        @Override
        public void setMatrix(int cct) {
            if (mMatrixNightDisplay.length != 16) {
                Slog.d(TAG, "The display transformation matrix must be 4x4");
                return;
            }

            Matrix.setIdentityM(mMatrixNightDisplay, 0);

            final float squareTemperature = cct * cct;
            final float red = squareTemperature * mColorTempCoefficients[0]
                    + cct * mColorTempCoefficients[1] + mColorTempCoefficients[2];
            final float green = squareTemperature * mColorTempCoefficients[3]
                    + cct * mColorTempCoefficients[4] + mColorTempCoefficients[5];
            final float blue = squareTemperature * mColorTempCoefficients[6]
                    + cct * mColorTempCoefficients[7] + mColorTempCoefficients[8];
            mMatrixNightDisplay[0] = red;
            mMatrixNightDisplay[5] = green;
            mMatrixNightDisplay[10] = blue;
        }

        @Override
        public float[] getMatrix() {
            return isActivated() ? mMatrixNightDisplay : MATRIX_IDENTITY;
        }

        @Override
        public int getLevel() {
            return LEVEL_COLOR_MATRIX_NIGHT_DISPLAY;
        }
    };

    private final TintController mDisplayWhiteBalanceTintController = new TintController() {
        // Three chromaticity coordinates per color: X, Y, and Z
        private final int NUM_VALUES_PER_PRIMARY = 3;
        // Four colors: red, green, blue, and white
        private final int NUM_DISPLAY_PRIMARIES_VALS = 4 * NUM_VALUES_PER_PRIMARY;

        private final Object mLock = new Object();
        private int mTemperatureMin;
        private int mTemperatureMax;
        private int mTemperatureDefault;
        private float[] mDisplayNominalWhiteXYZ = new float[NUM_VALUES_PER_PRIMARY];
        private ColorSpace.Rgb mDisplayColorSpaceRGB;
        private float[] mChromaticAdaptationMatrix;
        private int mCurrentColorTemperature;
        private float[] mCurrentColorTemperatureXYZ;
        private boolean mSetUp = false;
        private float[] mMatrixDisplayWhiteBalance = new float[16];

        @Override
        public void setUp(Context context, boolean needsLinear) {
            mSetUp = false;

            final Resources res = getContext().getResources();
            final String[] displayPrimariesValues = res.getStringArray(
                    R.array.config_displayWhiteBalanceDisplayPrimaries);
            final String[] nominalWhiteValues = res.getStringArray(
                    R.array.config_displayWhiteBalanceDisplayNominalWhite);

            if (displayPrimariesValues.length != NUM_DISPLAY_PRIMARIES_VALS) {
                Slog.e(TAG, "Unexpected display white balance primaries resource length " +
                        displayPrimariesValues.length);
                return;
            }

            if (nominalWhiteValues.length != NUM_VALUES_PER_PRIMARY) {
                Slog.e(TAG, "Unexpected display white balance nominal white resource length " +
                        nominalWhiteValues.length);
                return;
            }

            float[] displayRedGreenBlueXYZ =
                new float[NUM_DISPLAY_PRIMARIES_VALS - NUM_VALUES_PER_PRIMARY];
            float[] displayWhiteXYZ = new float[NUM_VALUES_PER_PRIMARY];
            for (int i = 0; i < displayRedGreenBlueXYZ.length; i++) {
                displayRedGreenBlueXYZ[i] = Float.parseFloat(displayPrimariesValues[i]);
            }
            for (int i = 0; i < displayWhiteXYZ.length; i++) {
                displayWhiteXYZ[i] = Float.parseFloat(
                        displayPrimariesValues[displayRedGreenBlueXYZ.length + i]);
            }

            final ColorSpace.Rgb displayColorSpaceRGB = new ColorSpace.Rgb(
                "Display Color Space",
                displayRedGreenBlueXYZ,
                displayWhiteXYZ,
                2.2f // gamma, unused for display white balance
            );

            float[] displayNominalWhiteXYZ = new float[NUM_VALUES_PER_PRIMARY];
            for (int i = 0; i < nominalWhiteValues.length; i++) {
                displayNominalWhiteXYZ[i] = Float.parseFloat(nominalWhiteValues[i]);
            }

            final int colorTemperatureMin = res.getInteger(
                    R.integer.config_displayWhiteBalanceColorTemperatureMin);
            if (colorTemperatureMin <= 0) {
                Slog.e(TAG, "display white balance minimum temperature must be greater than 0");
                return;
            }

            final int colorTemperatureMax = res.getInteger(
                    R.integer.config_displayWhiteBalanceColorTemperatureMax);
            if (colorTemperatureMax < colorTemperatureMin) {
                Slog.e(TAG, "display white balance max temp must be greater or equal to min");
                return;
            }

            final int colorTemperature = res.getInteger(
                    R.integer.config_displayWhiteBalanceColorTemperatureDefault);

            synchronized (mLock) {
                mDisplayColorSpaceRGB = displayColorSpaceRGB;
                mDisplayNominalWhiteXYZ = displayNominalWhiteXYZ;
                mTemperatureMin = colorTemperatureMin;
                mTemperatureMax = colorTemperatureMax;
                mTemperatureDefault = colorTemperature;
                mSetUp = true;
            }

            setMatrix(mTemperatureDefault);
        }

        @Override
        public float[] getMatrix() {
            return mSetUp && isActivated() ? mMatrixDisplayWhiteBalance : MATRIX_IDENTITY;
        }

        @Override
        public void setMatrix(int cct) {
            if (!mSetUp) {
                Slog.w(TAG, "Can't set display white balance temperature: uninitialized");
                return;
            }

            if (cct < mTemperatureMin) {
                Slog.w(TAG, "Requested display color temperature is below allowed minimum");
                cct = mTemperatureMin;
            } else if (cct > mTemperatureMax) {
                Slog.w(TAG, "Requested display color temperature is above allowed maximum");
                cct = mTemperatureMax;
            }

            Slog.d(TAG, "setDisplayWhiteBalanceTemperatureMatrix: cct = " + cct);

            synchronized (mLock) {
                mCurrentColorTemperature = cct;

                // Adapt the display's nominal white point to match the requested CCT value
                mCurrentColorTemperatureXYZ = ColorSpace.cctToIlluminantdXyz(cct);

                mChromaticAdaptationMatrix =
                    ColorSpace.chromaticAdaptation(ColorSpace.Adaptation.BRADFORD,
                            mDisplayNominalWhiteXYZ, mCurrentColorTemperatureXYZ);

                // Convert the adaptation matrix to RGB space
                float[] result = ColorSpace.mul3x3(mChromaticAdaptationMatrix,
                        mDisplayColorSpaceRGB.getTransform());
                result = ColorSpace.mul3x3(mDisplayColorSpaceRGB.getInverseTransform(), result);

                // Normalize the transform matrix to peak white value in RGB space
                final float adaptedMaxR = result[0] + result[3] + result[6];
                final float adaptedMaxG = result[1] + result[4] + result[7];
                final float adaptedMaxB = result[2] + result[5] + result[8];
                final float denum = Math.max(Math.max(adaptedMaxR, adaptedMaxG), adaptedMaxB);
                for (int i = 0; i < result.length; i++) {
                    result[i] /= denum;
                }

                Matrix.setIdentityM(mMatrixDisplayWhiteBalance, 0);
                java.lang.System.arraycopy(result, 0, mMatrixDisplayWhiteBalance, 0, 3);
                java.lang.System.arraycopy(result, 3, mMatrixDisplayWhiteBalance, 4, 3);
                java.lang.System.arraycopy(result, 6, mMatrixDisplayWhiteBalance, 8, 3);
            }
        }

        @Override
        public int getLevel() {
            return LEVEL_COLOR_MATRIX_DISPLAY_WHITE_BALANCE;
        }

        /**
         * Format a given matrix into a string.
         *
         * @param matrix the matrix to format
         * @param cols number of columns in the matrix
         */
        private String matrixToString(float[] matrix, int cols) {
            if (matrix == null || cols <= 0) {
                Slog.e(TAG, "Invalid arguments when formatting matrix to string");
                return "";
            }

            StringBuilder sb = new StringBuilder("");
            for (int i = 0; i < matrix.length; i++) {
                if (i % cols == 0) {
                    sb.append("\n    ");
                }
                sb.append(String.format("%9.6f ", matrix[i]));
            }
            return sb.toString();
        }

        @Override
        public void dump(PrintWriter pw) {
            synchronized (mLock) {
                pw.println("ColorDisplayService");
                pw.println("  mSetUp = " + mSetUp);

                if (!mSetUp) {
                    return;
                }

                pw.println("  isActivated = " + isActivated());
                pw.println("  mTemperatureMin = " + mTemperatureMin);
                pw.println("  mTemperatureMax = " + mTemperatureMax);
                pw.println("  mTemperatureDefault = " + mTemperatureDefault);
                pw.println("  mCurrentColorTemperature = " + mCurrentColorTemperature);
                pw.println("  mCurrentColorTemperatureXYZ = " +
                        matrixToString(mCurrentColorTemperatureXYZ, 3));
                pw.println("  mDisplayColorSpaceRGB RGB-to-XYZ = " +
                        matrixToString(mDisplayColorSpaceRGB.getTransform(), 3));
                pw.println("  mChromaticAdaptationMatrix = " +
                        matrixToString(mChromaticAdaptationMatrix, 3));
                pw.println("  mDisplayColorSpaceRGB XYZ-to-RGB = " +
                        matrixToString(mDisplayColorSpaceRGB.getInverseTransform(), 3));
                pw.println("  mMatrixDisplayWhiteBalance = " +
                        matrixToString(mMatrixDisplayWhiteBalance, 4));
            }
        }
    };

    private final TintController mGlobalSaturationTintController = new TintController() {

        private float[] mMatrixGlobalSaturation = new float[16];

        @Override
        public void setUp(Context context, boolean needsLinear) {
        }

        @Override
        public float[] getMatrix() {
            return Arrays.copyOf(mMatrixGlobalSaturation, mMatrixGlobalSaturation.length);
        }

        @Override
        public void setMatrix(int saturationLevel) {
            if (saturationLevel < 0) {
                saturationLevel = 0;
            } else if (saturationLevel > 100) {
                saturationLevel = 100;
            }
            Slog.d(TAG, "Setting saturation level: " + saturationLevel);

            if (saturationLevel == 100) {
                Matrix.setIdentityM(mMatrixGlobalSaturation, 0);
            } else {
                float saturation = saturationLevel * 0.1f;
                float desaturation = 1.0f - saturation;
                float[] luminance = {0.231f * desaturation, 0.715f * desaturation,
                    0.072f * desaturation};
                mMatrixGlobalSaturation[0] = luminance[0] + saturation;
                mMatrixGlobalSaturation[1] = luminance[0];
                mMatrixGlobalSaturation[2] = luminance[0];
                mMatrixGlobalSaturation[4] = luminance[1];
                mMatrixGlobalSaturation[5] = luminance[1] + saturation;
                mMatrixGlobalSaturation[6] = luminance[1];
                mMatrixGlobalSaturation[8] = luminance[2];
                mMatrixGlobalSaturation[9] = luminance[2];
                mMatrixGlobalSaturation[10] = luminance[2] + saturation;
            }
        }

        @Override
        public int getLevel() {
            return LEVEL_COLOR_MATRIX_SATURATION;
        }
    };

    /**
     * Matrix and offset used for converting color to grayscale.
     */
    private static final float[] MATRIX_GRAYSCALE = new float[]{
            .2126f, .2126f, .2126f, 0f,
            .7152f, .7152f, .7152f, 0f,
            .0722f, .0722f, .0722f, 0f,
            0f, 0f, 0f, 1f
    };

    /**
     * Matrix and offset used for luminance inversion. Represents a transform from RGB to YIQ color
     * space, rotation around the Y axis by 180 degrees, transform back to RGB color space, and
     * subtraction from 1. The last row represents a non-multiplied addition, see surfaceflinger's
     * ProgramCache for full implementation details.
     */
    private static final float[] MATRIX_INVERT_COLOR = new float[] {
            0.402f, -0.598f, -0.599f, 0f,
            -1.174f, -0.174f, -1.175f, 0f,
            -0.228f, -0.228f, 0.772f, 0f,
            1f, 1f, 1f, 1f
    };

    private final Handler mHandler;

    private final AppSaturationController mAppSaturationController = new AppSaturationController();

    private int mCurrentUser = UserHandle.USER_NULL;
    private ContentObserver mUserSetupObserver;
    private boolean mBootCompleted;

    private ColorDisplayController mNightDisplayController;
    private ContentObserver mContentObserver;

    private DisplayWhiteBalanceListener mDisplayWhiteBalanceListener;

    private NightDisplayAutoMode mNightDisplayAutoMode;

    public ColorDisplayService(Context context) {
        super(context);
        mHandler = new TintHandler(Looper.getMainLooper());
    }

    @Override
    public void onStart() {
        publishBinderService(Context.COLOR_DISPLAY_SERVICE, new BinderService());
        publishLocalService(ColorDisplayServiceInternal.class, new ColorDisplayServiceInternal());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase >= PHASE_BOOT_COMPLETED) {
            mBootCompleted = true;

            // Register listeners now that boot is complete.
            if (mCurrentUser != UserHandle.USER_NULL && mUserSetupObserver == null) {
                setUp();
            }
        }
    }

    @Override
    public void onStartUser(int userHandle) {
        super.onStartUser(userHandle);

        if (mCurrentUser == UserHandle.USER_NULL) {
            onUserChanged(userHandle);
        }
    }

    @Override
    public void onSwitchUser(int userHandle) {
        super.onSwitchUser(userHandle);

        onUserChanged(userHandle);
    }

    @Override
    public void onStopUser(int userHandle) {
        super.onStopUser(userHandle);

        if (mCurrentUser == userHandle) {
            onUserChanged(UserHandle.USER_NULL);
        }
    }

    private void onUserChanged(int userHandle) {
        final ContentResolver cr = getContext().getContentResolver();

        if (mCurrentUser != UserHandle.USER_NULL) {
            if (mUserSetupObserver != null) {
                cr.unregisterContentObserver(mUserSetupObserver);
                mUserSetupObserver = null;
            } else if (mBootCompleted) {
                tearDown();
            }
        }

        mCurrentUser = userHandle;

        if (mCurrentUser != UserHandle.USER_NULL) {
            if (!isUserSetupCompleted(cr, mCurrentUser)) {
                mUserSetupObserver = new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange, Uri uri) {
                        if (isUserSetupCompleted(cr, mCurrentUser)) {
                            cr.unregisterContentObserver(this);
                            mUserSetupObserver = null;

                            if (mBootCompleted) {
                                setUp();
                            }
                        }
                    }
                };
                cr.registerContentObserver(Secure.getUriFor(Secure.USER_SETUP_COMPLETE),
                        false /* notifyForDescendants */, mUserSetupObserver, mCurrentUser);
            } else if (mBootCompleted) {
                setUp();
            }
        }
    }

    private static boolean isUserSetupCompleted(ContentResolver cr, int userHandle) {
        return Secure.getIntForUser(cr, Secure.USER_SETUP_COMPLETE, 0, userHandle) == 1;
    }

    private void setUp() {
        Slog.d(TAG, "setUp: currentUser=" + mCurrentUser);

        mNightDisplayController = new ColorDisplayController(getContext(), mCurrentUser);

        // Listen for external changes to any of the settings.
        if (mContentObserver == null) {
            mContentObserver = new ContentObserver(new Handler(DisplayThread.get().getLooper())) {
                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    super.onChange(selfChange, uri);

                    final String setting = uri == null ? null : uri.getLastPathSegment();
                    if (setting != null) {
                        switch (setting) {
                            case Secure.NIGHT_DISPLAY_ACTIVATED:
                                onNightDisplayActivated(mNightDisplayController.isActivated());
                                break;
                            case Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE:
                                onNightDisplayColorTemperatureChanged(
                                        mNightDisplayController.getColorTemperature());
                                break;
                            case Secure.NIGHT_DISPLAY_AUTO_MODE:
                                onNightDisplayAutoModeChanged(
                                        mNightDisplayController.getAutoMode());
                                break;
                            case Secure.NIGHT_DISPLAY_CUSTOM_START_TIME:
                                onNightDisplayCustomStartTimeChanged(
                                        mNightDisplayController.getCustomStartTime());
                                break;
                            case Secure.NIGHT_DISPLAY_CUSTOM_END_TIME:
                                onNightDisplayCustomEndTimeChanged(
                                        mNightDisplayController.getCustomEndTime());
                                break;
                            case System.DISPLAY_COLOR_MODE:
                                onDisplayColorModeChanged(mNightDisplayController.getColorMode());
                                break;
                            case Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED:
                                onAccessibilityInversionChanged();
                                onAccessibilityActivated();
                                break;
                            case Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED:
                                onAccessibilityDaltonizerChanged();
                                onAccessibilityActivated();
                                break;
                            case Secure.ACCESSIBILITY_DISPLAY_DALTONIZER:
                                onAccessibilityDaltonizerChanged();
                                break;
                            case Secure.DISPLAY_WHITE_BALANCE_ENABLED:
                                updateDisplayWhiteBalanceStatus();
                                break;
                        }
                    }
                }
            };
        }
        final ContentResolver cr = getContext().getContentResolver();
        cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_ACTIVATED),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_COLOR_TEMPERATURE),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_AUTO_MODE),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_CUSTOM_START_TIME),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(Secure.getUriFor(Secure.NIGHT_DISPLAY_CUSTOM_END_TIME),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(System.getUriFor(System.DISPLAY_COLOR_MODE),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(
                Secure.getUriFor(Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(
                Secure.getUriFor(Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(
                Secure.getUriFor(Secure.ACCESSIBILITY_DISPLAY_DALTONIZER),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);
        cr.registerContentObserver(Secure.getUriFor(Secure.DISPLAY_WHITE_BALANCE_ENABLED),
                false /* notifyForDescendants */, mContentObserver, mCurrentUser);

        // Set the color mode, if valid, and immediately apply the updated tint matrix based on the
        // existing activated state. This ensures consistency of tint across the color mode change.
        onDisplayColorModeChanged(mNightDisplayController.getColorMode());

        if (ColorDisplayManager.isNightDisplayAvailable(getContext())) {
            // Reset the activated state.
            mNightDisplayTintController.setActivated(null);

            // Prepare the night display color transformation matrix.
            mNightDisplayTintController
                    .setUp(getContext(), DisplayTransformManager.needsLinearColorMatrix());
            mNightDisplayTintController.setMatrix(mNightDisplayController.getColorTemperature());

            // Initialize the current auto mode.
            onNightDisplayAutoModeChanged(mNightDisplayController.getAutoMode());

            // Force the initialization current activated state.
            if (mNightDisplayTintController.isActivatedStateNotSet()) {
                onNightDisplayActivated(mNightDisplayController.isActivated());
            }
        }

        if (ColorDisplayManager.isDisplayWhiteBalanceAvailable(getContext())) {
            // Prepare the display white balance transform matrix.
            mDisplayWhiteBalanceTintController.setUp(getContext(), true /* needsLinear */);

            updateDisplayWhiteBalanceStatus();
        }
    }

    private void tearDown() {
        Slog.d(TAG, "tearDown: currentUser=" + mCurrentUser);

        getContext().getContentResolver().unregisterContentObserver(mContentObserver);

        if (mNightDisplayController != null) {
            mNightDisplayController = null;
        }

        if (ColorDisplayManager.isNightDisplayAvailable(getContext())) {
            if (mNightDisplayAutoMode != null) {
                mNightDisplayAutoMode.onStop();
                mNightDisplayAutoMode = null;
            }
            mNightDisplayTintController.endAnimator();
        }

        if (ColorDisplayManager.isDisplayWhiteBalanceAvailable(getContext())) {
            mDisplayWhiteBalanceTintController.endAnimator();
        }
    }

    private void onNightDisplayActivated(boolean activated) {
        if (mNightDisplayTintController.isActivatedStateNotSet()
                || mNightDisplayTintController.isActivated() != activated) {
            Slog.i(TAG, activated ? "Turning on night display" : "Turning off night display");

            mNightDisplayTintController.setActivated(activated);

            if (mNightDisplayAutoMode != null) {
                mNightDisplayAutoMode.onActivated(activated);
            }

            updateDisplayWhiteBalanceStatus();

            applyTint(mNightDisplayTintController, false);
        }
    }

    private void onNightDisplayAutoModeChanged(int autoMode) {
        Slog.d(TAG, "onNightDisplayAutoModeChanged: autoMode=" + autoMode);

        if (mNightDisplayAutoMode != null) {
            mNightDisplayAutoMode.onStop();
            mNightDisplayAutoMode = null;
        }

        if (autoMode == ColorDisplayController.AUTO_MODE_CUSTOM) {
            mNightDisplayAutoMode = new CustomNightDisplayAutoMode();
        } else if (autoMode == ColorDisplayController.AUTO_MODE_TWILIGHT) {
            mNightDisplayAutoMode = new TwilightNightDisplayAutoMode();
        }

        if (mNightDisplayAutoMode != null) {
            mNightDisplayAutoMode.onStart();
        }
    }

    private void onNightDisplayCustomStartTimeChanged(LocalTime startTime) {
        Slog.d(TAG, "onNightDisplayCustomStartTimeChanged: startTime=" + startTime);

        if (mNightDisplayAutoMode != null) {
            mNightDisplayAutoMode.onCustomStartTimeChanged(startTime);
        }
    }

    private void onNightDisplayCustomEndTimeChanged(LocalTime endTime) {
        Slog.d(TAG, "onNightDisplayCustomEndTimeChanged: endTime=" + endTime);

        if (mNightDisplayAutoMode != null) {
            mNightDisplayAutoMode.onCustomEndTimeChanged(endTime);
        }
    }

    private void onNightDisplayColorTemperatureChanged(int colorTemperature) {
        mNightDisplayTintController.setMatrix(colorTemperature);
        applyTint(mNightDisplayTintController, true);
    }

    private void onDisplayColorModeChanged(int mode) {
        if (mode == -1) {
            return;
        }

        mNightDisplayTintController.cancelAnimator();
        mDisplayWhiteBalanceTintController.cancelAnimator();

        mNightDisplayTintController
                .setUp(getContext(), DisplayTransformManager.needsLinearColorMatrix(mode));
        mNightDisplayTintController.setMatrix(mNightDisplayController.getColorTemperature());

        updateDisplayWhiteBalanceStatus();

        final DisplayTransformManager dtm = getLocalService(DisplayTransformManager.class);
        dtm.setColorMode(mode, mNightDisplayTintController.getMatrix());
    }

    private void onAccessibilityActivated() {
        onDisplayColorModeChanged(mNightDisplayController.getColorMode());
    }

    /**
     * Apply the accessibility daltonizer transform based on the settings value.
     */
    private void onAccessibilityDaltonizerChanged() {
        final boolean enabled = Secure.getIntForUser(getContext().getContentResolver(),
                Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, 0, mCurrentUser) != 0;
        final int daltonizerMode = enabled ? Secure.getIntForUser(getContext().getContentResolver(),
                Secure.ACCESSIBILITY_DISPLAY_DALTONIZER,
                AccessibilityManager.DALTONIZER_CORRECT_DEUTERANOMALY, mCurrentUser)
                : AccessibilityManager.DALTONIZER_DISABLED;

        final DisplayTransformManager dtm = getLocalService(DisplayTransformManager.class);
        if (daltonizerMode == AccessibilityManager.DALTONIZER_SIMULATE_MONOCHROMACY) {
            // Monochromacy isn't supported by the native Daltonizer implementation; use grayscale.
            dtm.setColorMatrix(DisplayTransformManager.LEVEL_COLOR_MATRIX_GRAYSCALE,
                    MATRIX_GRAYSCALE);
            dtm.setDaltonizerMode(AccessibilityManager.DALTONIZER_DISABLED);
        } else {
            dtm.setColorMatrix(DisplayTransformManager.LEVEL_COLOR_MATRIX_GRAYSCALE, null);
            dtm.setDaltonizerMode(daltonizerMode);
        }
    }

    /**
     * Apply the accessibility inversion transform based on the settings value.
     */
    private void onAccessibilityInversionChanged() {
        final boolean enabled = Secure.getIntForUser(getContext().getContentResolver(),
                Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, 0, mCurrentUser) != 0;
        final DisplayTransformManager dtm = getLocalService(DisplayTransformManager.class);
        dtm.setColorMatrix(DisplayTransformManager.LEVEL_COLOR_MATRIX_INVERT_COLOR,
                enabled ? MATRIX_INVERT_COLOR : null);
    }

    /**
     * Applies current color temperature matrix, or removes it if deactivated.
     *
     * @param immediate {@code true} skips transition animation
     */
    private void applyTint(TintController tintController, boolean immediate) {
        tintController.cancelAnimator();

        final DisplayTransformManager dtm = getLocalService(DisplayTransformManager.class);
        final float[] from = dtm.getColorMatrix(tintController.getLevel());
        final float[] to = tintController.getMatrix();

        if (immediate) {
            dtm.setColorMatrix(tintController.getLevel(), to);
        } else {
            tintController.setAnimator(ValueAnimator.ofObject(COLOR_MATRIX_EVALUATOR,
                    from == null ? MATRIX_IDENTITY : from, to));
            tintController.getAnimator().setDuration(TRANSITION_DURATION);
            tintController.getAnimator().setInterpolator(AnimationUtils.loadInterpolator(
                    getContext(), android.R.interpolator.fast_out_slow_in));
            tintController.getAnimator().addUpdateListener((ValueAnimator animator) -> {
                final float[] value = (float[]) animator.getAnimatedValue();
                dtm.setColorMatrix(tintController.getLevel(), value);
            });
            tintController.getAnimator().addListener(new AnimatorListenerAdapter() {

                private boolean mIsCancelled;

                @Override
                public void onAnimationCancel(Animator animator) {
                    mIsCancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator animator) {
                    if (!mIsCancelled) {
                        // Ensure final color matrix is set at the end of the animation. If the
                        // animation is cancelled then don't set the final color matrix so the new
                        // animator can pick up from where this one left off.
                        dtm.setColorMatrix(tintController.getLevel(), to);
                    }
                    tintController.setAnimator(null);
                }
            });
            tintController.getAnimator().start();
        }
    }

    /**
     * Returns the first date time corresponding to the local time that occurs before the provided
     * date time.
     *
     * @param compareTime the LocalDateTime to compare against
     * @return the prior LocalDateTime corresponding to this local time
     */
    @VisibleForTesting
    static LocalDateTime getDateTimeBefore(LocalTime localTime, LocalDateTime compareTime) {
        final LocalDateTime ldt = LocalDateTime.of(compareTime.getYear(), compareTime.getMonth(),
                compareTime.getDayOfMonth(), localTime.getHour(), localTime.getMinute());

        // Check if the local time has passed, if so return the same time yesterday.
        return ldt.isAfter(compareTime) ? ldt.minusDays(1) : ldt;
    }

    /**
     * Returns the first date time corresponding to this local time that occurs after the provided
     * date time.
     *
     * @param compareTime the LocalDateTime to compare against
     * @return the next LocalDateTime corresponding to this local time
     */
    @VisibleForTesting
    static LocalDateTime getDateTimeAfter(LocalTime localTime, LocalDateTime compareTime) {
        final LocalDateTime ldt = LocalDateTime.of(compareTime.getYear(), compareTime.getMonth(),
                compareTime.getDayOfMonth(), localTime.getHour(), localTime.getMinute());

        // Check if the local time has passed, if so return the same time tomorrow.
        return ldt.isBefore(compareTime) ? ldt.plusDays(1) : ldt;
    }

    private void updateDisplayWhiteBalanceStatus() {
        boolean oldActivated = mDisplayWhiteBalanceTintController.isActivated();
        mDisplayWhiteBalanceTintController.setActivated(isDisplayWhiteBalanceSettingEnabled() &&
                !mNightDisplayTintController.isActivated() &&
                DisplayTransformManager.needsLinearColorMatrix());
        boolean activated = mDisplayWhiteBalanceTintController.isActivated();

        if (mDisplayWhiteBalanceListener != null && oldActivated != activated) {
            mDisplayWhiteBalanceListener.onDisplayWhiteBalanceStatusChanged(activated);
        }
    }

    private boolean isDisplayWhiteBalanceSettingEnabled() {
        return Secure.getIntForUser(getContext().getContentResolver(),
                Secure.DISPLAY_WHITE_BALANCE_ENABLED, 0, mCurrentUser) == 1;
    }

    private boolean isDeviceColorManagedInternal() {
        final DisplayTransformManager dtm = getLocalService(DisplayTransformManager.class);
        return dtm.isDeviceColorManaged();
    }

    /**
     * Returns the last time the night display transform activation state was changed, or {@link
     * LocalDateTime#MIN} if night display has never been activated.
     */
    private LocalDateTime getNightDisplayLastActivatedTimeSetting() {
        final ContentResolver cr = getContext().getContentResolver();
        final String lastActivatedTime = Secure.getStringForUser(
                cr, Secure.NIGHT_DISPLAY_LAST_ACTIVATED_TIME, getContext().getUserId());
        if (lastActivatedTime != null) {
            try {
                return LocalDateTime.parse(lastActivatedTime);
            } catch (DateTimeParseException ignored) {
            }
            // Uses the old epoch time.
            try {
                return LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(Long.parseLong(lastActivatedTime)),
                        ZoneId.systemDefault());
            } catch (DateTimeException | NumberFormatException ignored) {
            }
        }
        return LocalDateTime.MIN;
    }

    private boolean setAppSaturationLevelInternal(String packageName, int saturationLevel) {
        return mAppSaturationController
                .setSaturationLevel(packageName, mCurrentUser, saturationLevel);
    }

    private void dumpInternal(PrintWriter pw) {
        pw.println("COLOR DISPLAY MANAGER dumpsys (color_display)");
        pw.println("Night Display:");
        if (ColorDisplayManager.isNightDisplayAvailable(getContext())) {
            pw.println("    Activated: " + mNightDisplayTintController.isActivated());
        } else {
            pw.println("    Not available");
        }
        mAppSaturationController.dump(pw);
    }

    private abstract class NightDisplayAutoMode {

        public abstract void onActivated(boolean activated);

        public abstract void onStart();

        public abstract void onStop();

        public void onCustomStartTimeChanged(LocalTime startTime) {
        }

        public void onCustomEndTimeChanged(LocalTime endTime) {
        }
    }

    private final class CustomNightDisplayAutoMode extends NightDisplayAutoMode implements
            AlarmManager.OnAlarmListener {

        private final AlarmManager mAlarmManager;
        private final BroadcastReceiver mTimeChangedReceiver;

        private LocalTime mStartTime;
        private LocalTime mEndTime;

        private LocalDateTime mLastActivatedTime;

        CustomNightDisplayAutoMode() {
            mAlarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
            mTimeChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    updateActivated();
                }
            };
        }

        private void updateActivated() {
            final LocalDateTime now = LocalDateTime.now();
            final LocalDateTime start = getDateTimeBefore(mStartTime, now);
            final LocalDateTime end = getDateTimeAfter(mEndTime, start);
            boolean activate = now.isBefore(end);

            if (mLastActivatedTime != null) {
                // Maintain the existing activated state if within the current period.
                if (mLastActivatedTime.isBefore(now) && mLastActivatedTime.isAfter(start)
                        && (mLastActivatedTime.isAfter(end) || now.isBefore(end))) {
                    activate = mNightDisplayController.isActivated();
                }
            }

            if (mNightDisplayTintController.isActivatedStateNotSet() || (
                    mNightDisplayTintController.isActivated() != activate)) {
                mNightDisplayController.setActivated(activate);
            }

            updateNextAlarm(mNightDisplayTintController.isActivated(), now);
        }

        private void updateNextAlarm(@Nullable Boolean activated, @NonNull LocalDateTime now) {
            if (activated != null) {
                final LocalDateTime next = activated ? getDateTimeAfter(mEndTime, now)
                        : getDateTimeAfter(mStartTime, now);
                final long millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                mAlarmManager.setExact(AlarmManager.RTC, millis, TAG, this, null);
            }
        }

        @Override
        public void onStart() {
            final IntentFilter intentFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
            intentFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            getContext().registerReceiver(mTimeChangedReceiver, intentFilter);

            mStartTime = mNightDisplayController.getCustomStartTime();
            mEndTime = mNightDisplayController.getCustomEndTime();

            mLastActivatedTime = getNightDisplayLastActivatedTimeSetting();

            // Force an update to initialize state.
            updateActivated();
        }

        @Override
        public void onStop() {
            getContext().unregisterReceiver(mTimeChangedReceiver);

            mAlarmManager.cancel(this);
            mLastActivatedTime = null;
        }

        @Override
        public void onActivated(boolean activated) {
            mLastActivatedTime = getNightDisplayLastActivatedTimeSetting();
            updateNextAlarm(activated, LocalDateTime.now());
        }

        @Override
        public void onCustomStartTimeChanged(LocalTime startTime) {
            mStartTime = startTime;
            mLastActivatedTime = null;
            updateActivated();
        }

        @Override
        public void onCustomEndTimeChanged(LocalTime endTime) {
            mEndTime = endTime;
            mLastActivatedTime = null;
            updateActivated();
        }

        @Override
        public void onAlarm() {
            Slog.d(TAG, "onAlarm");
            updateActivated();
        }
    }

    private final class TwilightNightDisplayAutoMode extends NightDisplayAutoMode implements
            TwilightListener {

        private final TwilightManager mTwilightManager;
        private LocalDateTime mLastActivatedTime;

        TwilightNightDisplayAutoMode() {
            mTwilightManager = getLocalService(TwilightManager.class);
        }

        private void updateActivated(TwilightState state) {
            if (state == null) {
                // If there isn't a valid TwilightState then just keep the current activated
                // state.
                return;
            }

            boolean activate = state.isNight();
            if (mLastActivatedTime != null) {
                final LocalDateTime now = LocalDateTime.now();
                final LocalDateTime sunrise = state.sunrise();
                final LocalDateTime sunset = state.sunset();
                // Maintain the existing activated state if within the current period.
                if (mLastActivatedTime.isBefore(now) && (mLastActivatedTime.isBefore(sunrise)
                        ^ mLastActivatedTime.isBefore(sunset))) {
                    activate = mNightDisplayController.isActivated();
                }
            }

            if (mNightDisplayTintController.isActivatedStateNotSet() || (
                    mNightDisplayTintController.isActivated() != activate)) {
                mNightDisplayController.setActivated(activate);
            }
        }

        @Override
        public void onActivated(boolean activated) {
            mLastActivatedTime = getNightDisplayLastActivatedTimeSetting();
        }

        @Override
        public void onStart() {
            mTwilightManager.registerListener(this, mHandler);
            mLastActivatedTime = getNightDisplayLastActivatedTimeSetting();

            // Force an update to initialize state.
            updateActivated(mTwilightManager.getLastTwilightState());
        }

        @Override
        public void onStop() {
            mTwilightManager.unregisterListener(this);
            mLastActivatedTime = null;
        }

        @Override
        public void onTwilightStateChanged(@Nullable TwilightState state) {
            Slog.d(TAG, "onTwilightStateChanged: isNight="
                    + (state == null ? null : state.isNight()));
            updateActivated(state);
        }
    }

    /**
     * Interpolates between two 4x4 color transform matrices (in column-major order).
     */
    private static class ColorMatrixEvaluator implements TypeEvaluator<float[]> {

        /**
         * Result matrix returned by {@link #evaluate(float, float[], float[])}.
         */
        private final float[] mResultMatrix = new float[16];

        @Override
        public float[] evaluate(float fraction, float[] startValue, float[] endValue) {
            for (int i = 0; i < mResultMatrix.length; i++) {
                mResultMatrix[i] = MathUtils.lerp(startValue[i], endValue[i], fraction);
            }
            return mResultMatrix;
        }
    }

    private abstract static class TintController {

        private ValueAnimator mAnimator;
        private Boolean mIsActivated;

        public ValueAnimator getAnimator() {
            return mAnimator;
        }

        public void setAnimator(ValueAnimator animator) {
            mAnimator = animator;
        }

        /**
         * Cancel the animator if it's still running.
         */
        public void cancelAnimator() {
            if (mAnimator != null) {
                mAnimator.cancel();
            }
        }

        /**
         * End the animator if it's still running, jumping to the end state.
         */
        public void endAnimator() {
            if (mAnimator != null) {
                mAnimator.end();
                mAnimator = null;
            }
        }

        public void setActivated(Boolean isActivated) {
            mIsActivated = isActivated;
        }

        public boolean isActivated() {
            return mIsActivated != null && mIsActivated;
        }

        public boolean isActivatedStateNotSet() {
            return mIsActivated == null;
        }

        /**
         * Dump debug information.
         */
        public void dump(PrintWriter pw) {
        }

        /**
         * Set up any constants needed for computing the matrix.
         */
        public abstract void setUp(Context context, boolean needsLinear);

        /**
         * Sets the 4x4 matrix to apply.
         */
        public abstract void setMatrix(int value);

        /**
         * Get the 4x4 matrix to apply.
         */
        public abstract float[] getMatrix();

        /**
         * Get the color transform level to apply the matrix.
         */
        public abstract int getLevel();
    }

    /**
     * Local service that allows color transforms to be enabled from other system services.
     */
    public final class ColorDisplayServiceInternal {

        /**
         * Set the current CCT value for the display white balance transform, and if the transform
         * is enabled, apply it.
         *
         * @param cct the color temperature in Kelvin.
         */
        public boolean setDisplayWhiteBalanceColorTemperature(int cct) {
            // Update the transform matrix even if it can't be applied.
            mDisplayWhiteBalanceTintController.setMatrix(cct);

            if (mDisplayWhiteBalanceTintController.isActivated()) {
                applyTint(mDisplayWhiteBalanceTintController, false);
                return true;
            }
            return false;
        }

        /**
         * Sets the listener and returns whether display white balance is currently enabled.
         */
        public boolean setDisplayWhiteBalanceListener(DisplayWhiteBalanceListener listener) {
            mDisplayWhiteBalanceListener = listener;
            return mDisplayWhiteBalanceTintController.isActivated();
        }

        public void dump(PrintWriter pw) {
            mDisplayWhiteBalanceTintController.dump(pw);
        }

        /**
         * Adds a {@link WeakReference<ColorTransformController>} for a newly started activity, and
         * invokes {@link ColorTransformController#applyAppSaturation(float[], float[])} if needed.
         */
        public boolean attachColorTransformController(String packageName, int uid,
                WeakReference<ColorTransformController> controller) {
            return mAppSaturationController
                    .addColorTransformController(packageName, uid, controller);
        }
    }

    /**
     * Listener for changes in display white balance status.
     */
    public interface DisplayWhiteBalanceListener {

        /**
         * Notify that the display white balance status has changed, either due to preemption by
         * another transform or the feature being turned off.
         */
        void onDisplayWhiteBalanceStatusChanged(boolean enabled);
    }

    private final class TintHandler extends Handler {

        TintHandler(Looper looper) {
            super(looper, null, true /* async */);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_APPLY_GLOBAL_SATURATION:
                    mGlobalSaturationTintController.setMatrix(msg.arg1);
                    applyTint(mGlobalSaturationTintController, false);
                    break;
            }
        }
    }

    /**
     * Interface for applying transforms to a given AppWindow.
     */
    public interface ColorTransformController {

        /** Apply the given saturation (grayscale) matrix to the associated AppWindow. */
        void applyAppSaturation(@Size(9) float[] matrix, @Size(3) float[] translation);
    }

    private final class BinderService extends IColorDisplayManager.Stub {

        @Override
        public boolean isDeviceColorManaged() {
            final long token = Binder.clearCallingIdentity();
            try {
                return isDeviceColorManagedInternal();
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public boolean setSaturationLevel(int level) {
            final boolean hasTransformsPermission = getContext()
                    .checkCallingPermission(Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS)
                    == PackageManager.PERMISSION_GRANTED;
            final boolean hasLegacyPermission = getContext()
                    .checkCallingPermission(Manifest.permission.CONTROL_DISPLAY_SATURATION)
                    == PackageManager.PERMISSION_GRANTED;
            if (!hasTransformsPermission && !hasLegacyPermission) {
                throw new SecurityException("Permission required to set display saturation level");
            }
            final long token = Binder.clearCallingIdentity();
            try {
                final Message message = mHandler.obtainMessage(MSG_APPLY_GLOBAL_SATURATION);
                message.arg1 = level;
                mHandler.sendMessage(message);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
            return true;
        }

        @Override
        public boolean setAppSaturationLevel(String packageName, int level) {
            getContext().enforceCallingPermission(
                    Manifest.permission.CONTROL_DISPLAY_COLOR_TRANSFORMS,
                    "Permission required to set display saturation level");
            final long token = Binder.clearCallingIdentity();
            try {
                return setAppSaturationLevelInternal(packageName, level);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        @Override
        public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, pw)) return;

            final long token = Binder.clearCallingIdentity();
            try {
                dumpInternal(pw);
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }
    }
}
