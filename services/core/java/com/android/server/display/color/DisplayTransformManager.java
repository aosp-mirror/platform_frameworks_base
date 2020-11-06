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

package com.android.server.display.color;

import android.app.ActivityTaskManager;
import android.hardware.display.ColorDisplayManager;
import android.opengl.Matrix;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Display;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Arrays;

/**
 * Manager for applying color transformations to the display.
 */
public class DisplayTransformManager {

    private static final String TAG = "DisplayTransformManager";

    private static final String SURFACE_FLINGER = "SurfaceFlinger";

    /**
     * Color transform level used by Night display to tint the display red.
     */
    public static final int LEVEL_COLOR_MATRIX_NIGHT_DISPLAY = 100;
    /**
     * Color transform level used by display white balance to adjust the display's white point.
     */
    public static final int LEVEL_COLOR_MATRIX_DISPLAY_WHITE_BALANCE = 125;
    /**
     * Color transform level used to adjust the color saturation of the display.
     */
    public static final int LEVEL_COLOR_MATRIX_SATURATION = 150;
    /**
     * Color transform level used by A11y services to make the display monochromatic.
     */
    public static final int LEVEL_COLOR_MATRIX_GRAYSCALE = 200;
    /**
     * Color transform level used by A11y services to reduce bright colors.
     */
    public static final int LEVEL_COLOR_MATRIX_REDUCE_BRIGHT_COLORS = 250;
    /**
     * Color transform level used by A11y services to invert the display colors.
     */
    public static final int LEVEL_COLOR_MATRIX_INVERT_COLOR = 300;

    private static final int SURFACE_FLINGER_TRANSACTION_COLOR_MATRIX = 1015;
    private static final int SURFACE_FLINGER_TRANSACTION_DALTONIZER = 1014;
    /**
     * SurfaceFlinger global saturation factor.
     */
    private static final int SURFACE_FLINGER_TRANSACTION_SATURATION = 1022;
    /**
     * SurfaceFlinger display color (managed, unmanaged, etc.).
     */
    private static final int SURFACE_FLINGER_TRANSACTION_DISPLAY_COLOR = 1023;
    private static final int SURFACE_FLINGER_TRANSACTION_QUERY_COLOR_MANAGED = 1030;

    @VisibleForTesting
    static final String PERSISTENT_PROPERTY_SATURATION = "persist.sys.sf.color_saturation";
    @VisibleForTesting
    static final String PERSISTENT_PROPERTY_COMPOSITION_COLOR_MODE = "persist.sys.sf.color_mode";
    @VisibleForTesting
    static final String PERSISTENT_PROPERTY_DISPLAY_COLOR = "persist.sys.sf.native_mode";

    private static final float COLOR_SATURATION_NATURAL = 1.0f;
    private static final float COLOR_SATURATION_BOOSTED = 1.1f;

    /**
     * Display color modes defined by DisplayColorSetting in
     * frameworks/native/services/surfaceflinger/SurfaceFlinger.h.
     */
    private static final int DISPLAY_COLOR_MANAGED = 0;
    private static final int DISPLAY_COLOR_UNMANAGED = 1;
    private static final int DISPLAY_COLOR_ENHANCED = 2;

    /**
     * Map of level -> color transformation matrix.
     */
    @GuardedBy("mColorMatrix")
    private final SparseArray<float[]> mColorMatrix = new SparseArray<>(6);
    /**
     * Temporary matrix used internally by {@link #computeColorMatrixLocked()}.
     */
    @GuardedBy("mColorMatrix")
    private final float[][] mTempColorMatrix = new float[2][16];

    /**
     * Lock used for synchronize access to {@link #mDaltonizerMode}.
     */
    private final Object mDaltonizerModeLock = new Object();
    @GuardedBy("mDaltonizerModeLock")
    private int mDaltonizerMode = -1;

    private static final IBinder sFlinger = ServiceManager.getService(SURFACE_FLINGER);

    /* package */ DisplayTransformManager() {
    }

    /**
     * Returns a copy of the color transform matrix set for a given level.
     */
    public float[] getColorMatrix(int key) {
        synchronized (mColorMatrix) {
            final float[] value = mColorMatrix.get(key);
            return value == null ? null : Arrays.copyOf(value, value.length);
        }
    }

    /**
     * Sets and applies a current color transform matrix for a given level.
     * <p>
     * Note: all color transforms are first composed to a single matrix in ascending order based on
     * level before being applied to the display.
     *
     * @param level the level used to identify and compose the color transform (low -> high)
     * @param value the 4x4 color transform matrix (in column-major order), or {@code null} to
     * remove the color transform matrix associated with the provided level
     */
    public void setColorMatrix(int level, float[] value) {
        if (value != null && value.length != 16) {
            throw new IllegalArgumentException("Expected length: 16 (4x4 matrix)"
                    + ", actual length: " + value.length);
        }

        synchronized (mColorMatrix) {
            final float[] oldValue = mColorMatrix.get(level);
            if (!Arrays.equals(oldValue, value)) {
                if (value == null) {
                    mColorMatrix.remove(level);
                } else if (oldValue == null) {
                    mColorMatrix.put(level, Arrays.copyOf(value, value.length));
                } else {
                    System.arraycopy(value, 0, oldValue, 0, value.length);
                }

                // Update the current color transform.
                applyColorMatrix(computeColorMatrixLocked());
            }
        }
    }

    /**
     * Sets the current Daltonization mode. This adjusts the color space to correct for or simulate
     * various types of color blindness.
     *
     * @param mode the new Daltonization mode, or -1 to disable
     */
    public void setDaltonizerMode(int mode) {
        synchronized (mDaltonizerModeLock) {
            if (mDaltonizerMode != mode) {
                mDaltonizerMode = mode;
                applyDaltonizerMode(mode);
            }
        }
    }

    /**
     * Returns the composition of all current color matrices, or {@code null} if there are none.
     */
    @GuardedBy("mColorMatrix")
    private float[] computeColorMatrixLocked() {
        final int count = mColorMatrix.size();
        if (count == 0) {
            return null;
        }

        final float[][] result = mTempColorMatrix;
        Matrix.setIdentityM(result[0], 0);
        for (int i = 0; i < count; i++) {
            float[] rhs = mColorMatrix.valueAt(i);
            Matrix.multiplyMM(result[(i + 1) % 2], 0, result[i % 2], 0, rhs, 0);
        }
        return result[count % 2];
    }

    /**
     * Propagates the provided color transformation matrix to the SurfaceFlinger.
     */
    private static void applyColorMatrix(float[] m) {
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
        try {
            sFlinger.transact(SURFACE_FLINGER_TRANSACTION_COLOR_MATRIX, data, null, 0);
        } catch (RemoteException ex) {
            Slog.e(TAG, "Failed to set color transform", ex);
        } finally {
            data.recycle();
        }
    }

    /**
     * Propagates the provided Daltonization mode to the SurfaceFlinger.
     */
    private static void applyDaltonizerMode(int mode) {
        final Parcel data = Parcel.obtain();
        data.writeInterfaceToken("android.ui.ISurfaceComposer");
        data.writeInt(mode);
        try {
            sFlinger.transact(SURFACE_FLINGER_TRANSACTION_DALTONIZER, data, null, 0);
        } catch (RemoteException ex) {
            Slog.e(TAG, "Failed to set Daltonizer mode", ex);
        } finally {
            data.recycle();
        }
    }

    /**
     * Return true when the color matrix works in linear space.
     */
    public static boolean needsLinearColorMatrix() {
        return SystemProperties.getInt(PERSISTENT_PROPERTY_DISPLAY_COLOR,
                DISPLAY_COLOR_UNMANAGED) != DISPLAY_COLOR_UNMANAGED;
    }

    /**
     * Return true when the specified colorMode requires the color matrix to work in linear space.
     */
    public static boolean needsLinearColorMatrix(int colorMode) {
        return colorMode != ColorDisplayManager.COLOR_MODE_SATURATED;
    }

    /**
     * Sets color mode and updates night display transform values.
     */
    public boolean setColorMode(int colorMode, float[] nightDisplayMatrix,
            int compositionColorMode) {
        if (colorMode == ColorDisplayManager.COLOR_MODE_NATURAL) {
            applySaturation(COLOR_SATURATION_NATURAL);
            setDisplayColor(DISPLAY_COLOR_MANAGED, compositionColorMode);
        } else if (colorMode == ColorDisplayManager.COLOR_MODE_BOOSTED) {
            applySaturation(COLOR_SATURATION_BOOSTED);
            setDisplayColor(DISPLAY_COLOR_MANAGED, compositionColorMode);
        } else if (colorMode == ColorDisplayManager.COLOR_MODE_SATURATED) {
            applySaturation(COLOR_SATURATION_NATURAL);
            setDisplayColor(DISPLAY_COLOR_UNMANAGED, compositionColorMode);
        } else if (colorMode == ColorDisplayManager.COLOR_MODE_AUTOMATIC) {
            applySaturation(COLOR_SATURATION_NATURAL);
            setDisplayColor(DISPLAY_COLOR_ENHANCED, compositionColorMode);
        } else if (colorMode >= ColorDisplayManager.VENDOR_COLOR_MODE_RANGE_MIN
                && colorMode <= ColorDisplayManager.VENDOR_COLOR_MODE_RANGE_MAX) {
            applySaturation(COLOR_SATURATION_NATURAL);
            setDisplayColor(colorMode, compositionColorMode);
        }

        setColorMatrix(LEVEL_COLOR_MATRIX_NIGHT_DISPLAY, nightDisplayMatrix);

        updateConfiguration();

        return true;
    }

    /**
     * Returns whether the screen is color managed via SurfaceFlinger's {@link
     * #SURFACE_FLINGER_TRANSACTION_QUERY_COLOR_MANAGED}.
     */
    public boolean isDeviceColorManaged() {
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        data.writeInterfaceToken("android.ui.ISurfaceComposer");
        try {
            sFlinger.transact(SURFACE_FLINGER_TRANSACTION_QUERY_COLOR_MANAGED, data, reply, 0);
            return reply.readBoolean();
        } catch (RemoteException ex) {
            Slog.e(TAG, "Failed to query wide color support", ex);
        } finally {
            data.recycle();
            reply.recycle();
        }
        return false;
    }

    /**
     * Propagates the provided saturation to the SurfaceFlinger.
     */
    private void applySaturation(float saturation) {
        SystemProperties.set(PERSISTENT_PROPERTY_SATURATION, Float.toString(saturation));
        final Parcel data = Parcel.obtain();
        data.writeInterfaceToken("android.ui.ISurfaceComposer");
        data.writeFloat(saturation);
        try {
            sFlinger.transact(SURFACE_FLINGER_TRANSACTION_SATURATION, data, null, 0);
        } catch (RemoteException ex) {
            Slog.e(TAG, "Failed to set saturation", ex);
        } finally {
            data.recycle();
        }
    }

    /**
     * Toggles native mode on/off in SurfaceFlinger.
     */
    private void setDisplayColor(int color, int compositionColorMode) {
        SystemProperties.set(PERSISTENT_PROPERTY_DISPLAY_COLOR, Integer.toString(color));
        if (compositionColorMode != Display.COLOR_MODE_INVALID) {
            SystemProperties.set(PERSISTENT_PROPERTY_COMPOSITION_COLOR_MODE,
                Integer.toString(compositionColorMode));
        }

        final Parcel data = Parcel.obtain();
        data.writeInterfaceToken("android.ui.ISurfaceComposer");
        data.writeInt(color);
        if (compositionColorMode != Display.COLOR_MODE_INVALID) {
            data.writeInt(compositionColorMode);
        }
        try {
            sFlinger.transact(SURFACE_FLINGER_TRANSACTION_DISPLAY_COLOR, data, null, 0);
        } catch (RemoteException ex) {
            Slog.e(TAG, "Failed to set display color", ex);
        } finally {
            data.recycle();
        }
    }

    private void updateConfiguration() {
        try {
            ActivityTaskManager.getService().updateConfiguration(null);
        } catch (RemoteException e) {
            Slog.e(TAG, "Could not update configuration", e);
        }
    }
}
