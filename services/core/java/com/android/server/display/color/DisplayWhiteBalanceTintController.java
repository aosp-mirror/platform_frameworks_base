/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.display.color;

import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.server.display.color.DisplayTransformManager.LEVEL_COLOR_MATRIX_DISPLAY_WHITE_BALANCE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.Size;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.ColorSpace;
import android.hardware.display.ColorDisplayManager;
import android.hardware.display.DisplayManagerInternal;
import android.opengl.Matrix;
import android.util.Slog;
import android.view.SurfaceControl.DisplayPrimaries;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.feature.DisplayManagerFlags;

import java.io.PrintWriter;

final class DisplayWhiteBalanceTintController extends ColorTemperatureTintController {

    // Three chromaticity coordinates per color: X, Y, and Z
    private static final int NUM_VALUES_PER_PRIMARY = 3;
    // Four colors: red, green, blue, and white
    private static final int NUM_DISPLAY_PRIMARIES_VALS = 4 * NUM_VALUES_PER_PRIMARY;
    private static final int COLORSPACE_MATRIX_LENGTH = 9;

    private final Object mLock = new Object();
    @VisibleForTesting
    int mTemperatureMin;
    @VisibleForTesting
    int mTemperatureMax;
    private int mTemperatureDefault;
    @VisibleForTesting
    float[] mDisplayNominalWhiteXYZ = new float[NUM_VALUES_PER_PRIMARY];
    private int mDisplayNominalWhiteCct;
    @VisibleForTesting
    ColorSpace.Rgb mDisplayColorSpaceRGB;
    private float[] mChromaticAdaptationMatrix;
    // The temperature currently represented in the matrix.
    @VisibleForTesting
    int mCurrentColorTemperature;
    private float[] mCurrentColorTemperatureXYZ;
    @VisibleForTesting
    boolean mSetUp = false;
    private final float[] mMatrixDisplayWhiteBalance = new float[16];
    private long mTransitionDuration;
    private long mTransitionDurationIncrease;
    private long mTransitionDurationDecrease;
    private Boolean mIsAvailable;
    // This feature becomes disallowed if the device is in an unsupported strong/light state.
    private boolean mIsAllowed = true;
    private int mTargetCct;
    private int mAppliedCct;
    private CctEvaluator mCctEvaluator;

    private final DisplayManagerInternal mDisplayManagerInternal;

    private final DisplayManagerFlags mDisplayManagerFlags;

    DisplayWhiteBalanceTintController(DisplayManagerInternal dm,
            DisplayManagerFlags displayManagerFlags) {
        mDisplayManagerInternal = dm;
        mDisplayManagerFlags = displayManagerFlags;
    }

    @Override
    public void setUp(Context context, boolean needsLinear) {
        mSetUp = false;
        final Resources res = context.getResources();

        // Initialize with the config value for light mode, so it starts in the right state.
        setAllowed(res.getBoolean(R.bool.config_displayWhiteBalanceLightModeAllowed));

        ColorSpace.Rgb displayColorSpaceRGB = getDisplayColorSpaceFromSurfaceControl();
        if (displayColorSpaceRGB == null) {
            Slog.w(ColorDisplayService.TAG,
                    "Failed to get display color space from SurfaceControl, trying res");
            displayColorSpaceRGB = getDisplayColorSpaceFromResources(res);
            if (displayColorSpaceRGB == null) {
                Slog.e(ColorDisplayService.TAG, "Failed to get display color space from resources");
                return;
            }
        }

        // Make sure display color space is valid
        if (!isColorMatrixValid(displayColorSpaceRGB.getTransform())) {
            Slog.e(ColorDisplayService.TAG, "Invalid display color space RGB-to-XYZ transform");
            return;
        }
        if (!isColorMatrixValid(displayColorSpaceRGB.getInverseTransform())) {
            Slog.e(ColorDisplayService.TAG, "Invalid display color space XYZ-to-RGB transform");
            return;
        }

        final String[] nominalWhiteValues = res.getStringArray(
                R.array.config_displayWhiteBalanceDisplayNominalWhite);
        float[] displayNominalWhiteXYZ = new float[NUM_VALUES_PER_PRIMARY];
        for (int i = 0; i < nominalWhiteValues.length; i++) {
            displayNominalWhiteXYZ[i] = Float.parseFloat(nominalWhiteValues[i]);
        }

        final int displayNominalWhiteCct = res.getInteger(
                R.integer.config_displayWhiteBalanceDisplayNominalWhiteCct);

        final int colorTemperatureMin = res.getInteger(
                R.integer.config_displayWhiteBalanceColorTemperatureMin);
        if (colorTemperatureMin <= 0) {
            Slog.e(ColorDisplayService.TAG,
                    "Display white balance minimum temperature must be greater than 0");
            return;
        }

        final int colorTemperatureMax = res.getInteger(
                R.integer.config_displayWhiteBalanceColorTemperatureMax);
        if (colorTemperatureMax < colorTemperatureMin) {
            Slog.e(ColorDisplayService.TAG,
                    "Display white balance max temp must be greater or equal to min");
            return;
        }

        final int defaultTemperature = res.getInteger(
                R.integer.config_displayWhiteBalanceColorTemperatureDefault);

        mTransitionDuration = res.getInteger(
                R.integer.config_displayWhiteBalanceTransitionTime);

        if (!mDisplayManagerFlags.isAdaptiveTone2Enabled()) {
            mTransitionDurationDecrease = mTransitionDuration;
            mTransitionDurationIncrease = mTransitionDuration;
        } else {
            mTransitionDurationIncrease = res.getInteger(
                    R.integer.config_displayWhiteBalanceTransitionTimeIncrease);
            mTransitionDurationDecrease = res.getInteger(
                    R.integer.config_displayWhiteBalanceTransitionTimeDecrease);
        }

        int[] cctRangeMinimums = res.getIntArray(
                R.array.config_displayWhiteBalanceDisplayRangeMinimums);
        int[] steps = res.getIntArray(R.array.config_displayWhiteBalanceDisplaySteps);

        synchronized (mLock) {
            mDisplayColorSpaceRGB = displayColorSpaceRGB;
            mDisplayNominalWhiteXYZ = displayNominalWhiteXYZ;
            mDisplayNominalWhiteCct = displayNominalWhiteCct;
            mTargetCct = mDisplayNominalWhiteCct;
            mAppliedCct = mDisplayNominalWhiteCct;
            mTemperatureMin = colorTemperatureMin;
            mTemperatureMax = colorTemperatureMax;
            mTemperatureDefault = defaultTemperature;
            mSetUp = true;
            mCctEvaluator = new CctEvaluator(mTemperatureMin, mTemperatureMax,
                    cctRangeMinimums, steps);
        }

        setMatrix(mTemperatureDefault);
    }

    @Override
    public float[] getMatrix() {
        if (!mSetUp || !isActivated()) {
            return ColorDisplayService.MATRIX_IDENTITY;
        }
        computeMatrixForCct(mAppliedCct);
        return mMatrixDisplayWhiteBalance;
    }

    @Override
    public int getTargetCct() {
        return mTargetCct;
    }

    /**
     * Multiplies two 3x3 matrices, represented as non-null arrays of 9 floats.
     *
     * @param lhs 3x3 matrix, as a non-null array of 9 floats
     * @param rhs 3x3 matrix, as a non-null array of 9 floats
     * @return A new array of 9 floats containing the result of the multiplication
     *         of rhs by lhs
     */
    @NonNull
    @Size(9)
    private static float[] mul3x3(@NonNull @Size(9) float[] lhs, @NonNull @Size(9) float[] rhs) {
        float[] r = new float[9];
        r[0] = lhs[0] * rhs[0] + lhs[3] * rhs[1] + lhs[6] * rhs[2];
        r[1] = lhs[1] * rhs[0] + lhs[4] * rhs[1] + lhs[7] * rhs[2];
        r[2] = lhs[2] * rhs[0] + lhs[5] * rhs[1] + lhs[8] * rhs[2];
        r[3] = lhs[0] * rhs[3] + lhs[3] * rhs[4] + lhs[6] * rhs[5];
        r[4] = lhs[1] * rhs[3] + lhs[4] * rhs[4] + lhs[7] * rhs[5];
        r[5] = lhs[2] * rhs[3] + lhs[5] * rhs[4] + lhs[8] * rhs[5];
        r[6] = lhs[0] * rhs[6] + lhs[3] * rhs[7] + lhs[6] * rhs[8];
        r[7] = lhs[1] * rhs[6] + lhs[4] * rhs[7] + lhs[7] * rhs[8];
        r[8] = lhs[2] * rhs[6] + lhs[5] * rhs[7] + lhs[8] * rhs[8];
        return r;
    }

    @Override
    public void setMatrix(int cct) {
        setTargetCct(cct);
        computeMatrixForCct(mTargetCct);
    }

    @Override
    public void setTargetCct(int cct) {
        if (!mSetUp) {
            Slog.w(ColorDisplayService.TAG,
                    "Can't set display white balance temperature: uninitialized");
            return;
        }

        if (cct < mTemperatureMin) {
            Slog.w(ColorDisplayService.TAG,
                    "Requested display color temperature is below allowed minimum");
            mTargetCct = mTemperatureMin;
        } else if (cct > mTemperatureMax) {
            Slog.w(ColorDisplayService.TAG,
                    "Requested display color temperature is above allowed maximum");
            mTargetCct = mTemperatureMax;
        } else {
            mTargetCct = cct;
        }
    }

    @Override
    public int getDisabledCct() {
        return mDisplayNominalWhiteCct;
    }

    @Override
    public float[] computeMatrixForCct(int cct) {
        if (!mSetUp || cct == 0) {
            Slog.w(ColorDisplayService.TAG, "Couldn't compute matrix for cct=" + cct);
            return ColorDisplayService.MATRIX_IDENTITY;
        }

        synchronized (mLock) {
            mCurrentColorTemperature = cct;

            if (cct == mDisplayNominalWhiteCct && !isActivated()) {
                // DWB is finished turning off. Clear the matrix.
                Matrix.setIdentityM(mMatrixDisplayWhiteBalance, 0);
            } else {
                computeMatrixForCctLocked(cct);
            }

            Slog.d(ColorDisplayService.TAG, "computeDisplayWhiteBalanceMatrix: cct =" + cct
                    + " matrix =" + matrixToString(mMatrixDisplayWhiteBalance, 16));

            return mMatrixDisplayWhiteBalance;
        }
    }

    private void computeMatrixForCctLocked(int cct) {
        // Adapt the display's nominal white point to match the requested CCT value
        mCurrentColorTemperatureXYZ = ColorSpace.cctToXyz(cct);

        mChromaticAdaptationMatrix =
                ColorSpace.chromaticAdaptation(ColorSpace.Adaptation.BRADFORD,
                        mDisplayNominalWhiteXYZ, mCurrentColorTemperatureXYZ);

        // Convert the adaptation matrix to RGB space
        float[] result = mul3x3(mChromaticAdaptationMatrix,
                mDisplayColorSpaceRGB.getTransform());
        result = mul3x3(mDisplayColorSpaceRGB.getInverseTransform(), result);

        // Normalize the transform matrix to peak white value in RGB space
        final float adaptedMaxR = result[0] + result[3] + result[6];
        final float adaptedMaxG = result[1] + result[4] + result[7];
        final float adaptedMaxB = result[2] + result[5] + result[8];
        final float denum = Math.max(Math.max(adaptedMaxR, adaptedMaxG), adaptedMaxB);

        Matrix.setIdentityM(mMatrixDisplayWhiteBalance, 0);

        for (int i = 0; i < result.length; i++) {
            result[i] /= denum;
            if (!isColorMatrixCoeffValid(result[i])) {
                Slog.e(ColorDisplayService.TAG, "Invalid DWB color matrix");
                return;
            }
        }

        java.lang.System.arraycopy(result, 0, mMatrixDisplayWhiteBalance, 0, 3);
        java.lang.System.arraycopy(result, 3, mMatrixDisplayWhiteBalance, 4, 3);
        java.lang.System.arraycopy(result, 6, mMatrixDisplayWhiteBalance, 8, 3);
    }

    @Override
    int getAppliedCct() {
        return mAppliedCct;
    }

    @Override
    void setAppliedCct(int cct) {
        mAppliedCct = cct;
    }

    @Override
    @Nullable
    CctEvaluator getEvaluator() {
        return mCctEvaluator;
    }

    @Override
    public int getLevel() {
        return LEVEL_COLOR_MATRIX_DISPLAY_WHITE_BALANCE;
    }

    @Override
    public boolean isAvailable(Context context) {
        if (mIsAvailable == null) {
            mIsAvailable = ColorDisplayManager.isDisplayWhiteBalanceAvailable(context);
        }
        return mIsAvailable;
    }

    @Override
    public long getTransitionDurationMilliseconds() {
        return mTransitionDuration;
    }

    @Override
    public long getTransitionDurationMilliseconds(boolean isIncreasing) {
        return isIncreasing ? mTransitionDurationIncrease : mTransitionDurationDecrease;
    }

    @Override
    public void dump(PrintWriter pw) {
        synchronized (mLock) {
            pw.println("    mSetUp = " + mSetUp);
            if (!mSetUp) {
                return;
            }

            pw.println("    mTemperatureMin = " + mTemperatureMin);
            pw.println("    mTemperatureMax = " + mTemperatureMax);
            pw.println("    mTemperatureDefault = " + mTemperatureDefault);
            pw.println("    mDisplayNominalWhiteCct = " + mDisplayNominalWhiteCct);
            pw.println("    mCurrentColorTemperature = " + mCurrentColorTemperature);
            pw.println("    mTargetCct = " + mTargetCct);
            pw.println("    mAppliedCct = " + mAppliedCct);
            pw.println("    mCurrentColorTemperatureXYZ = "
                    + matrixToString(mCurrentColorTemperatureXYZ, 3));
            pw.println("    mDisplayColorSpaceRGB RGB-to-XYZ = "
                    + matrixToString(mDisplayColorSpaceRGB.getTransform(), 3));
            pw.println("    mChromaticAdaptationMatrix = "
                    + matrixToString(mChromaticAdaptationMatrix, 3));
            pw.println("    mDisplayColorSpaceRGB XYZ-to-RGB = "
                    + matrixToString(mDisplayColorSpaceRGB.getInverseTransform(), 3));
            pw.println("    mMatrixDisplayWhiteBalance = "
                    + matrixToString(mMatrixDisplayWhiteBalance, 4));
            pw.println("    mIsAllowed = " + mIsAllowed);
            pw.println("    mTransitionDuration = " + mTransitionDuration);
            pw.println("    mTransitionDurationIncrease = " + mTransitionDurationIncrease);
            pw.println("    mTransitionDurationDecrease = " + mTransitionDurationDecrease);
        }
    }

    public float getLuminance() {
        synchronized (mLock) {
            if (mChromaticAdaptationMatrix != null && mChromaticAdaptationMatrix.length == 9) {
                // Compute only the luminance (y) value of the xyz * [1 1 1] transform.
                return 1 / (mChromaticAdaptationMatrix[1] + mChromaticAdaptationMatrix[4]
                        + mChromaticAdaptationMatrix[7]);
            } else {
                return -1;
            }
        }
    }

    public void setAllowed(boolean allowed) {
        mIsAllowed = allowed;
    }

    public boolean isAllowed() {
        return mIsAllowed;
    }

    private ColorSpace.Rgb makeRgbColorSpaceFromXYZ(float[] redGreenBlueXYZ, float[] whiteXYZ) {
        return new ColorSpace.Rgb(
                "Display Color Space",
                redGreenBlueXYZ,
                whiteXYZ,
                2.2f // gamma, unused for display white balance
        );
    }

    private ColorSpace.Rgb getDisplayColorSpaceFromSurfaceControl() {
        DisplayPrimaries primaries =
                mDisplayManagerInternal.getDisplayNativePrimaries(DEFAULT_DISPLAY);
        if (primaries == null || primaries.red == null || primaries.green == null
                || primaries.blue == null || primaries.white == null) {
            return null;
        }

        return makeRgbColorSpaceFromXYZ(
                new float[]{
                        primaries.red.X, primaries.red.Y, primaries.red.Z,
                        primaries.green.X, primaries.green.Y, primaries.green.Z,
                        primaries.blue.X, primaries.blue.Y, primaries.blue.Z,
                },
                new float[]{primaries.white.X, primaries.white.Y, primaries.white.Z}
        );
    }

    private ColorSpace.Rgb getDisplayColorSpaceFromResources(Resources res) {
        final String[] displayPrimariesValues = res.getStringArray(
                R.array.config_displayWhiteBalanceDisplayPrimaries);
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

        return makeRgbColorSpaceFromXYZ(displayRedGreenBlueXYZ, displayWhiteXYZ);
    }

    private boolean isColorMatrixCoeffValid(float coeff) {
        return !Float.isNaN(coeff) && !Float.isInfinite(coeff);
    }

    private boolean isColorMatrixValid(float[] matrix) {
        if (matrix == null || matrix.length != COLORSPACE_MATRIX_LENGTH) {
            return false;
        }

        for (float value : matrix) {
            if (!isColorMatrixCoeffValid(value)) {
                return false;
            }
        }

        return true;
    }

}
