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

import android.opengl.Matrix;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Slog;
import android.util.SparseArray;

/**
 * Manager for applying color transformations to the display.
 */
public class DisplayTransformManager {

    private static final String TAG = "DisplayTransformManager";

    /**
     * Color transform level used by Night display to tint the display red.
     */
    public static final int LEVEL_COLOR_MATRIX_NIGHT_DISPLAY = 100;
    /**
     * Color transform level used by A11y services to make the display monochromatic.
     */
    public static final int LEVEL_COLOR_MATRIX_GRAYSCALE = 200;
    /**
     * Color transform level used by A11y services to invert the display colors.
     */
    public static final int LEVEL_COLOR_MATRIX_INVERT_COLOR = 300;

    private final SparseArray<float[]> mColorMatrix = new SparseArray<>(3);

    private int mDaltonizerMode = -1;

    /* package */ DisplayTransformManager() {
    }

    /**
     * Returns the color transform matrix set for a given level.
     */
    public float[] getColorMatrix(int key) {
        synchronized (mColorMatrix) {
            return mColorMatrix.get(key);
        }
    }

    /**
     * Sets and applies a current color transform matrix for a given level.
     * <p>
     * Note: all color transforms are first composed to a single matrix in ascending order based
     * on level before being applied to the display.
     *
     * @param key   the level used to identify and compose the color transform (low -> high)
     * @param value the 4x4 color transform matrix (in column-major order), or {@code null} to
     *              remove the color transform matrix associated with the provided level
     */
    public void setColorMatrix(int key, float[] value) {
        if (value != null && value.length != 16) {
            throw new IllegalArgumentException("Expected length: 16 (4x4 matrix)"
                    + ", actual length: " + value.length);
        }

        synchronized (mColorMatrix) {
            if (value != null) {
                mColorMatrix.put(key, value);
            } else {
                mColorMatrix.remove(key);
            }

            // Update the current color transform.
            applyColorMatrix(computeColorMatrix());
        }
    }

    /**
     * Returns the composition of all current color matrices, or {@code null} if there are none.
     */
    private float[] computeColorMatrix() {
        synchronized (mColorMatrix) {
            final int count = mColorMatrix.size();
            if (count == 0) {
                return null;
            }

            final float[][] result = new float[2][16];
            Matrix.setIdentityM(result[0], 0);
            for (int i = 0; i < count; i++) {
                float[] rhs = mColorMatrix.valueAt(i);
                Matrix.multiplyMM(result[(i + 1) % 2], 0, result[i % 2], 0, rhs, 0);
            }
            return result[count % 2];
        }
    }

    /**
     * Returns the current Daltonization mode.
     */
    public int getDaltonizerMode() {
        return mDaltonizerMode;
    }

    /**
     * Sets the current Daltonization mode. This adjusts the color space to correct for or simulate
     * various types of color blindness.
     *
     * @param mode the new Daltonization mode, or -1 to disable
     */
    public void setDaltonizerMode(int mode) {
        if (mDaltonizerMode != mode) {
            mDaltonizerMode = mode;
            applyDaltonizerMode(mode);
        }
    }

    /**
     * Propagates the provided color transformation matrix to the SurfaceFlinger.
     */
    private static void applyColorMatrix(float[] m) {
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
            try {
                flinger.transact(1015, data, null, 0);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to set color transform", ex);
            } finally {
                data.recycle();
            }
        }
    }

    /**
     * Propagates the provided Daltonization mode to the SurfaceFlinger.
     */
    private static void applyDaltonizerMode(int mode) {
        final IBinder flinger = ServiceManager.getService("SurfaceFlinger");
        if (flinger != null) {
            final Parcel data = Parcel.obtain();
            data.writeInterfaceToken("android.ui.ISurfaceComposer");
            data.writeInt(mode);
            try {
                flinger.transact(1014, data, null, 0);
            } catch (RemoteException ex) {
                Slog.e(TAG, "Failed to set Daltonizer mode", ex);
            } finally {
                data.recycle();
            }
        }
    }
}
