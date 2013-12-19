/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server.accessibility;

import android.content.ContentResolver;
import android.content.Context;
import android.opengl.Matrix;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Slog;
import android.view.accessibility.AccessibilityManager;

/**
 * Utility methods for performing accessibility display adjustments.
 */
class DisplayAdjustmentUtils {
    private static final String LOG_TAG = DisplayAdjustmentUtils.class.getSimpleName();

    /** Matrix and offset used for converting color to gray-scale. */
    private static final float[] GRAYSCALE_MATRIX = new float[] {
        .2126f, .2126f, .2126f, 0,
        .7152f, .7152f, .7152f, 0,
        .0722f, .0722f, .0722f, 0,
             0,      0,      0, 1
    };

    /** Matrix and offset used for standard display inversion. */
    private static final float[] INVERSION_MATRIX_STANDARD = new float[] {
        -1,  0,  0, 0,
         0, -1,  0, 0,
         0,  0, -1, 0,
         1,  1,  1, 1
    };

    /** Matrix and offset used for hue-only display inversion. */
    private static final float[] INVERSION_MATRIX_HUE_ONLY = new float[] {
          0, .5f, .5f, 0,
        .5f,   0, .5f, 0,
        .5f, .5f,   0, 0,
          0,   0,   0, 1
    };

    /** Matrix and offset used for value-only display inversion. */
    private static final float[] INVERSION_MATRIX_VALUE_ONLY = new float[] {
           0, -.5f, -.5f, 0,
        -.5f,    0, -.5f, 0,
        -.5f, -.5f,    0, 0,
           1,    1,    1, 1
    };

    /** Default contrast for display contrast enhancement. */
    private static final float DEFAULT_DISPLAY_CONTRAST = 2;

    /** Default brightness for display contrast enhancement. */
    private static final float DEFAULT_DISPLAY_BRIGHTNESS = 0;

    /** Default inversion mode for display color inversion. */
    private static final int DEFAULT_DISPLAY_INVERSION = AccessibilityManager.INVERSION_STANDARD;

    /** Default inversion mode for display color correction. */
    private static final int DEFAULT_DISPLAY_DALTONIZER =
            AccessibilityManager.DALTONIZER_CORRECT_DEUTERANOMALY;

    /**
     * Returns whether the specified user with has any display color
     * adjustments.
     */
    public static boolean hasAdjustments(Context context, int userId) {
        final ContentResolver cr = context.getContentResolver();

        boolean hasColorTransform = Settings.Secure.getIntForUser(
                cr, Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, 0, userId) == 1;

        if (!hasColorTransform) {
            hasColorTransform |= Settings.Secure.getIntForUser(
                cr, Settings.Secure.ACCESSIBILITY_DISPLAY_CONTRAST_ENABLED, 0, userId) == 1;
        }

        if (!hasColorTransform) {
            hasColorTransform |= Settings.Secure.getIntForUser(
                cr, Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, 0, userId) == 1;
        }

        return hasColorTransform;
    }

    /**
     * Applies the specified user's display color adjustments.
     */
    public static void applyAdjustments(Context context, int userId) {
        final ContentResolver cr = context.getContentResolver();
        float[] colorMatrix = new float[16];
        float[] outputMatrix = new float[16];
        boolean hasColorTransform = false;

        Matrix.setIdentityM(colorMatrix, 0);

        final boolean inversionEnabled = Settings.Secure.getIntForUser(
                cr, Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, 0, userId) == 1;
        if (inversionEnabled) {
            final int inversionMode = Settings.Secure.getIntForUser(cr,
                    Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION, DEFAULT_DISPLAY_INVERSION,
                    userId);
            final float[] inversionMatrix;
            switch (inversionMode) {
                case AccessibilityManager.INVERSION_HUE_ONLY:
                    inversionMatrix = INVERSION_MATRIX_HUE_ONLY;
                    break;
                case AccessibilityManager.INVERSION_VALUE_ONLY:
                    inversionMatrix = INVERSION_MATRIX_VALUE_ONLY;
                    break;
                default:
                    inversionMatrix = INVERSION_MATRIX_STANDARD;
            }

            Matrix.multiplyMM(outputMatrix, 0, colorMatrix, 0, inversionMatrix, 0);

            colorMatrix = outputMatrix;
            outputMatrix = colorMatrix;

            hasColorTransform = true;
        }

        final boolean contrastEnabled = Settings.Secure.getIntForUser(
                cr, Settings.Secure.ACCESSIBILITY_DISPLAY_CONTRAST_ENABLED, 0, userId) == 1;
        if (contrastEnabled) {
            final float contrast = Settings.Secure.getFloatForUser(cr,
                    Settings.Secure.ACCESSIBILITY_DISPLAY_CONTRAST, DEFAULT_DISPLAY_CONTRAST,
                    userId);
            final float brightness = Settings.Secure.getFloatForUser(cr,
                    Settings.Secure.ACCESSIBILITY_DISPLAY_BRIGHTNESS, DEFAULT_DISPLAY_BRIGHTNESS,
                    userId);
            final float off = brightness * contrast - 0.5f * contrast + 0.5f;
            final float[] contrastMatrix = {
                    contrast, 0, 0, 0,
                    0, contrast, 0, 0,
                    0, 0, contrast, 0,
                    off, off, off, 1
            };

            Matrix.multiplyMM(outputMatrix, 0, colorMatrix, 0, contrastMatrix, 0);

            colorMatrix = outputMatrix;
            outputMatrix = colorMatrix;

            hasColorTransform = true;
        }

        final boolean daltonizerEnabled = Settings.Secure.getIntForUser(
                cr, Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, 0, userId) != 0;
        if (daltonizerEnabled) {
            final int daltonizerMode = Settings.Secure.getIntForUser(cr,
                    Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER, DEFAULT_DISPLAY_DALTONIZER,
                    userId);
            // Monochromacy isn't supported by the native Daltonizer.
            if (daltonizerMode == AccessibilityManager.DALTONIZER_SIMULATE_MONOCHROMACY) {
                Matrix.multiplyMM(outputMatrix, 0, colorMatrix, 0, GRAYSCALE_MATRIX, 0);

                final float[] temp = colorMatrix;
                colorMatrix = outputMatrix;
                outputMatrix = temp;

                hasColorTransform = true;
                nativeSetDaltonizerMode(AccessibilityManager.DALTONIZER_DISABLED);
            } else {
                nativeSetDaltonizerMode(daltonizerMode);
            }
        } else {
            nativeSetDaltonizerMode(AccessibilityManager.DALTONIZER_DISABLED);
        }

        if (hasColorTransform) {
            nativeSetColorTransform(colorMatrix);
        } else {
            nativeSetColorTransform(null);
        }
    }

    /**
     * Sets the surface flinger's Daltonization mode. This adjusts the color
     * space to correct for or simulate various types of color blindness.
     *
     * @param mode new Daltonization mode
     */
    private static void nativeSetDaltonizerMode(int mode) {
        try {
            final IBinder flinger = ServiceManager.getService("SurfaceFlinger");
            if (flinger != null) {
                final Parcel data = Parcel.obtain();
                data.writeInterfaceToken("android.ui.ISurfaceComposer");
                data.writeInt(mode);
                flinger.transact(1014, data, null, 0);
                data.recycle();
            }
        } catch (RemoteException ex) {
            Slog.e(LOG_TAG, "Failed to set Daltonizer mode", ex);
        }
    }

    /**
     * Sets the surface flinger's color transformation as a 4x4 matrix. If the
     * matrix is null, color transformations are disabled.
     *
     * @param m the float array that holds the transformation matrix, or null to
     *            disable transformation
     */
    private static void nativeSetColorTransform(float[] m) {
        try {
            final IBinder flinger = ServiceManager.getService("SurfaceFlinger");
            if (flinger != null) {
                final Parcel data = Parcel.obtain();
                data.writeInterfaceToken("android.ui.ISurfaceComposer");
                if (m != null) {
                    data.writeInt(1);
                    for (int i = 0; i < 16; i++) {
                        data.writeFloat(m[i]);
                    }
                } else {
                    data.writeInt(0);
                }
                flinger.transact(1015, data, null, 0);
                data.recycle();
            }
        } catch (RemoteException ex) {
            Slog.e(LOG_TAG, "Failed to set color transform", ex);
        }
    }

}
