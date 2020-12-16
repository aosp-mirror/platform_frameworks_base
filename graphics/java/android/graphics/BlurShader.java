/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * A subclass of shader that blurs input from another {@link android.graphics.Shader} instance
 * or all the drawing commands with the {@link android.graphics.Paint} that this shader is
 * attached to.
 */
public final class BlurShader extends Shader {

    private final float mRadiusX;
    private final float mRadiusY;
    private final Shader mInputShader;
    private final TileMode mEdgeTreatment;

    private long mNativeInputShader = 0;

    /**
     * Create a {@link BlurShader} that blurs the contents of the optional input shader
     * with the specified radius along the x and y axis. If no input shader is provided
     * then all drawing commands issued with a {@link android.graphics.Paint} that this
     * shader is installed in will be blurred.
     *
     * This uses a default {@link TileMode#DECAL} for edge treatment
     *
     * @param radiusX Radius of blur along the X axis
     * @param radiusY Radius of blur along the Y axis
     * @param inputShader Input shader that provides the content to be blurred
     */
    public BlurShader(float radiusX, float radiusY, @Nullable Shader inputShader) {
        this(radiusX, radiusY, inputShader, TileMode.DECAL);
    }

    /**
     * Create a {@link BlurShader} that blurs the contents of the optional input shader
     * with the specified radius along the x and y axis. If no input shader is provided
     * then all drawing commands issued with a {@link android.graphics.Paint} that this
     * shader is installed in will be blurred
     * @param radiusX Radius of blur along the X axis
     * @param radiusY Radius of blur along the Y axis
     * @param inputShader Input shader that provides the content to be blurred
     * @param edgeTreatment Policy for how to blur content near edges of the blur shader
     */
    public BlurShader(float radiusX, float radiusY, @Nullable Shader inputShader,
            @NonNull TileMode edgeTreatment) {
        mRadiusX = radiusX;
        mRadiusY = radiusY;
        mInputShader = inputShader;
        mEdgeTreatment = edgeTreatment;
    }

    /** @hide **/
    @Override
    protected long createNativeInstance(long nativeMatrix, boolean filterFromPaint) {
        mNativeInputShader = mInputShader != null
                ? mInputShader.getNativeInstance(filterFromPaint) : 0;
        return nativeCreate(nativeMatrix, mRadiusX, mRadiusY, mNativeInputShader,
                mEdgeTreatment.nativeInt);
    }

    /** @hide **/
    @Override
    protected boolean shouldDiscardNativeInstance(boolean filterFromPaint) {
        long currentNativeInstance = mInputShader != null
                ? mInputShader.getNativeInstance(filterFromPaint) : 0;
        return mNativeInputShader != currentNativeInstance;
    }

    private static native long nativeCreate(long nativeMatrix, float radiusX, float radiusY,
            long inputShader, int edgeTreatment);
}
