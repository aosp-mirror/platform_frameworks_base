/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * Shader is the based class for objects that return horizontal spans of colors
 * during drawing. A subclass of Shader is installed in a Paint calling
 * paint.setShader(shader). After that any object (other than a bitmap) that is
 * drawn with that paint will get its color(s) from the shader.
 */
public class Shader {
    /**
     * @deprecated Use subclass constructors directly instead.
     */
    @Deprecated
    public Shader() {}

    /**
     * Current native shader instance. Created and updated lazily when {@link #getNativeInstance()}
     * is called - otherwise may be out of date with java setters/properties.
     */
    private long mNativeInstance;

    /**
     * Current matrix - always set to null if local matrix is identity.
     */
    private Matrix mLocalMatrix;

    public enum TileMode {
        /**
         * replicate the edge color if the shader draws outside of its
         * original bounds
         */
        CLAMP   (0),
        /**
         * repeat the shader's image horizontally and vertically
         */
        REPEAT  (1),
        /**
         * repeat the shader's image horizontally and vertically, alternating
         * mirror images so that adjacent images always seam
         */
        MIRROR  (2);
    
        TileMode(int nativeInt) {
            this.nativeInt = nativeInt;
        }
        final int nativeInt;
    }

    /**
     * Return true if the shader has a non-identity local matrix.
     * @param localM Set to the local matrix of the shader, if the shader's matrix is non-null.
     * @return true if the shader has a non-identity local matrix
     */
    public boolean getLocalMatrix(@NonNull Matrix localM) {
        if (mLocalMatrix != null) {
            localM.set(mLocalMatrix);
            return true; // presence of mLocalMatrix means it's not identity
        }
        return false;
    }

    /**
     * Set the shader's local matrix. Passing null will reset the shader's
     * matrix to identity.
     *
     * @param localM The shader's new local matrix, or null to specify identity
     */
    public void setLocalMatrix(@Nullable Matrix localM) {
        if (localM == null || localM.isIdentity()) {
            if (mLocalMatrix != null) {
                mLocalMatrix = null;
                discardNativeInstance();
            }
        } else {
            if (mLocalMatrix == null) {
                mLocalMatrix = new Matrix(localM);
                discardNativeInstance();
            } else if (!mLocalMatrix.equals(localM)) {
                mLocalMatrix.set(localM);
                discardNativeInstance();
            }
        }
    }

    long createNativeInstance(long nativeMatrix) {
        return 0;
    }

    void discardNativeInstance() {
        if (mNativeInstance != 0) {
            nativeSafeUnref(mNativeInstance);
            mNativeInstance = 0;
        }
    }

    /**
     * Callback for subclasses to call {@link #discardNativeInstance()} if the most recently
     * constructed native instance is no longer valid.
     */
    void verifyNativeInstance() {
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mNativeInstance != 0) {
                nativeSafeUnref(mNativeInstance);
            }
            mNativeInstance = -1;
        } finally {
            super.finalize();
        }
    }

    /**
     * @hide
     */
    protected Shader copy() {
        final Shader copy = new Shader();
        copyLocalMatrix(copy);
        return copy;
    }

    /**
     * @hide
     */
    protected void copyLocalMatrix(Shader dest) {
        dest.mLocalMatrix.set(mLocalMatrix);
    }

    /**
     * @hide
     */
    public long getNativeInstance() {
        if (mNativeInstance == -1) {
            throw new IllegalStateException("attempting to use a finalized Shader");
        }

        // verify mNativeInstance is valid
        verifyNativeInstance();

        if (mNativeInstance == 0) {
            mNativeInstance = createNativeInstance(mLocalMatrix == null
                    ? 0 : mLocalMatrix.native_instance);
        }
        return mNativeInstance;
    }

    private static native void nativeSafeUnref(long nativeInstance);
}
