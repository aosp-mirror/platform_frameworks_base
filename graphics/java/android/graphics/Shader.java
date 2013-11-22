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

/**
 * Shader is the based class for objects that return horizontal spans of colors
 * during drawing. A subclass of Shader is installed in a Paint calling
 * paint.setShader(shader). After that any object (other than a bitmap) that is
 * drawn with that paint will get its color(s) from the shader.
 */
public class Shader {
    /**
     * This is set by subclasses, but don't make it public.
     * 
     * @hide 
     */
    public int native_instance;
    /**
     * @hide
     */
    public int native_shader;

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
     * @param localM If not null, it is set to the shader's local matrix.
     * @return true if the shader has a non-identity local matrix
     */
    public boolean getLocalMatrix(Matrix localM) {
        if (mLocalMatrix != null) {
            localM.set(mLocalMatrix);
            return !mLocalMatrix.isIdentity();
        }
        return false;
    }

    /**
     * Set the shader's local matrix. Passing null will reset the shader's
     * matrix to identity
     * @param localM The shader's new local matrix, or null to specify identity
     */
    public void setLocalMatrix(Matrix localM) {
        mLocalMatrix = localM;
        nativeSetLocalMatrix(native_instance, native_shader,
                localM == null ? 0 : localM.native_instance);
    }

    protected void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            nativeDestructor(native_instance, native_shader);
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
        if (mLocalMatrix != null) {
            final Matrix lm = new Matrix();
            getLocalMatrix(lm);
            dest.setLocalMatrix(lm);
        } else {
            dest.setLocalMatrix(null);
        }
    }

    private static native void nativeDestructor(int native_shader, int native_skiaShader);
    private static native void nativeSetLocalMatrix(int native_shader,
            int native_skiaShader, int matrix_instance);
}
