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

    /**
     * Matrix and offset used for luminance inversion. Represents a transform
     * from RGB to YIQ color space, rotation around the Y axis by 180 degrees,
     * transform back to RGB color space, and subtraction from 1. The last row
     * represents a non-multiplied addition, see surfaceflinger's ProgramCache
     * for full implementation details.
     */
    private static final float[] INVERSION_MATRIX_VALUE_ONLY = new float[] {
        0.402f, -0.598f, -0.599f, 0,
       -1.174f, -0.174f, -1.175f, 0,
       -0.228f, -0.228f,  0.772f, 0,
             1,       1,       1, 1
    };

    /** Default inversion mode for display color correction. */
    private static final int DEFAULT_DISPLAY_DALTONIZER =
            AccessibilityManager.DALTONIZER_CORRECT_DEUTERANOMALY;

    /**
     * Returns whether the specified user with has any display color
     * adjustments.
     */
    public static boolean hasAdjustments(Context context, int userId) {
        final ContentResolver cr = context.getContentResolver();

        if (Settings.Secure.getIntForUser(cr,
                Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, 0, userId) != 0) {
            return true;
        }

        if (Settings.Secure.getIntForUser(cr,
                Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, 0, userId) != 0) {
            return true;
        }

        return false;
    }

    /**
     * Applies the specified user's display color adjustments.
     */
    public static void applyAdjustments(Context context, int userId) {
        final ContentResolver cr = context.getContentResolver();
        float[] colorMatrix = null;

        if (Settings.Secure.getIntForUser(cr,
                Settings.Secure.ACCESSIBILITY_DISPLAY_INVERSION_ENABLED, 0, userId) != 0) {
            colorMatrix = multiply(colorMatrix, INVERSION_MATRIX_VALUE_ONLY);
        }

        if (Settings.Secure.getIntForUser(cr,
                Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER_ENABLED, 0, userId) != 0) {
            final int daltonizerMode = Settings.Secure.getIntForUser(cr,
                    Settings.Secure.ACCESSIBILITY_DISPLAY_DALTONIZER, DEFAULT_DISPLAY_DALTONIZER,
                    userId);
            // Monochromacy isn't supported by the native Daltonizer.
            if (daltonizerMode == AccessibilityManager.DALTONIZER_SIMULATE_MONOCHROMACY) {
                colorMatrix = multiply(colorMatrix, GRAYSCALE_MATRIX);
                setDaltonizerMode(AccessibilityManager.DALTONIZER_DISABLED);
            } else {
                setDaltonizerMode(daltonizerMode);
            }
        } else {
            setDaltonizerMode(AccessibilityManager.DALTONIZER_DISABLED);
        }

        String matrix = Settings.Secure.getStringForUser(cr,
                Settings.Secure.ACCESSIBILITY_DISPLAY_COLOR_MATRIX, userId);
        if (matrix != null) {
            final float[] userMatrix = get4x4Matrix(matrix);
            if (userMatrix != null) {
                colorMatrix = multiply(colorMatrix, userMatrix);
            }
        }

        setColorTransform(colorMatrix);
    }

    private static float[] get4x4Matrix(String matrix) {
        String[] strValues = matrix.split(",");
        if (strValues.length != 16) {
            return null;
        }
        float[] values = new float[strValues.length];
        try {
            for (int i = 0; i < values.length; i++) {
                values[i] = Float.parseFloat(strValues[i]);
            }
        } catch (java.lang.NumberFormatException ex) {
            return null;
        }
        return values;
    }

    private static float[] multiply(float[] matrix, float[] other) {
        if (matrix == null) {
            return other;
        }
        float[] result = new float[16];
        Matrix.multiplyMM(result, 0, matrix, 0, other, 0);
        return result;
    }

    /**
     * Sets the surface flinger's Daltonization mode. This adjusts the color
     * space to correct for or simulate various types of color blindness.
     *
     * @param mode new Daltonization mode
     */
    private static void setDaltonizerMode(int mode) {
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
    private static void setColorTransform(float[] m) {
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
