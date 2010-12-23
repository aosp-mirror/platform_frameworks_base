/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.graphics;

import com.android.layoutlib.bridge.impl.DelegateManager;

import android.graphics.Shader.TileMode;

/**
 * Delegate implementing the native methods of android.graphics.Shader
 *
 * Through the layoutlib_create tool, the original native methods of Shader have been replaced
 * by calls to methods of the same name in this delegate class.
 *
 * This class behaves like the original native implementation, but in Java, keeping previously
 * native data into its own objects and mapping them to int that are sent back and forth between
 * it and the original Shader class.
 *
 * This also serve as a base class for all Shader delegate classes.
 *
 * @see DelegateManager
 *
 */
public abstract class Shader_Delegate {

    // ---- delegate manager ----
    protected static final DelegateManager<Shader_Delegate> sManager =
            new DelegateManager<Shader_Delegate>();

    // ---- delegate helper data ----

    // ---- delegate data ----
    private int mLocalMatrix = 0;

    // ---- Public Helper methods ----

    public static Shader_Delegate getDelegate(int nativeShader) {
        return sManager.getDelegate(nativeShader);
    }

    /**
     * Returns the {@link TileMode} matching the given int.
     * @param tileMode the tile mode int value
     * @return the TileMode enum.
     */
    public static TileMode getTileMode(int tileMode) {
        for (TileMode tm : TileMode.values()) {
            if (tm.nativeInt == tileMode) {
                return tm;
            }
        }

        assert false;
        return TileMode.CLAMP;
    }

    public abstract java.awt.Paint getJavaPaint();
    public abstract boolean isSupported();
    public abstract String getSupportMessage();

    // ---- native methods ----

    /*package*/ static void nativeDestructor(int native_shader, int native_skiaShader) {
        sManager.removeDelegate(native_shader);
    }

    /*package*/ static boolean nativeGetLocalMatrix(int native_shader, int matrix_instance) {
        // get the delegate from the native int.
        Shader_Delegate shaderDelegate = sManager.getDelegate(native_shader);
        if (shaderDelegate == null) {
            return false;
        }

        // can be null if shader has no matrix (int is 0)
        Matrix_Delegate localMatrixDelegate = Matrix_Delegate.getDelegate(
                shaderDelegate.mLocalMatrix);

        // can be null if the int is 0.
        Matrix_Delegate destMatrixDelegate = Matrix_Delegate.getDelegate(matrix_instance);
        if (destMatrixDelegate != null) {
            if (localMatrixDelegate != null) {
                destMatrixDelegate.set(localMatrixDelegate);
            } else {
                // since there's no local matrix, it's considered to be the identity, reset
                // the destination matrix
                destMatrixDelegate.reset();
            }
        }

        return localMatrixDelegate == null || localMatrixDelegate.isIdentity();
    }

    /*package*/ static void nativeSetLocalMatrix(int native_shader, int native_skiaShader,
            int matrix_instance) {
        // get the delegate from the native int.
        Shader_Delegate shaderDelegate = sManager.getDelegate(native_shader);
        if (shaderDelegate == null) {
            return;
        }

        shaderDelegate.mLocalMatrix = matrix_instance;
    }

    // ---- Private delegate/helper methods ----

    protected java.awt.geom.AffineTransform getLocalMatrix() {
        Matrix_Delegate localMatrixDelegate = Matrix_Delegate.getDelegate(mLocalMatrix);
        if (localMatrixDelegate != null) {
            return localMatrixDelegate.getAffineTransform();
        }

        return new java.awt.geom.AffineTransform();
    }

}
