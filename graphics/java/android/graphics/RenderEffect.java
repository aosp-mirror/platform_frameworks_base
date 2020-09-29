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
import android.graphics.Shader.TileMode;

import libcore.util.NativeAllocationRegistry;

/**
 * Intermediate rendering step used to render drawing commands with a corresponding
 * visual effect
 *
 * @hide
 */
public final class RenderEffect {

    private static class RenderEffectHolder {
        public static final NativeAllocationRegistry RENDER_EFFECT_REGISTRY =
                NativeAllocationRegistry.createMalloced(
                        RenderEffect.class.getClassLoader(), nativeGetFinalizer());
    }

    /**
     * Create a {@link RenderEffect} instance that will offset the drawing content
     * by the provided x and y offset.
     * @param offsetX offset along the x axis in pixels
     * @param offsetY offset along the y axis in pixels
     */
    @NonNull
    public static RenderEffect createOffsetEffect(float offsetX, float offsetY) {
        return new RenderEffect(nativeCreateOffsetEffect(offsetX, offsetY, 0));
    }

    /**
     * Create a {@link RenderEffect} instance with the provided x and y offset
     * @param offsetX offset along the x axis in pixels
     * @param offsetY offset along the y axis in pixels
     * @param input target RenderEffect used to render in the offset coordinates.
     */
    @NonNull
    public static RenderEffect createOffsetEffect(
            float offsetX,
            float offsetY,
            @NonNull RenderEffect input
    ) {
        return new RenderEffect(nativeCreateOffsetEffect(
                    offsetX,
                    offsetY,
                    input.getNativeInstance()
                )
        );
    }

    /**
     * Create a {@link RenderEffect} that blurs the contents of the optional input RenderEffect
     * with the specified radius along the x and y axis. If no input RenderEffect is provided
     * then all drawing commands issued with a {@link android.graphics.RenderNode} that this
     * RenderEffect is installed in will be blurred
     * @param radiusX Radius of blur along the X axis
     * @param radiusY Radius of blur along the Y axis
     * @param inputEffect Input RenderEffect that provides the content to be blurred, can be null
     *                    to indicate that the drawing commands on the RenderNode are to be
     *                    blurred instead of the input RenderEffect
     * @param edgeTreatment Policy for how to blur content near edges of the blur kernel
     */
    @NonNull
    public static RenderEffect createBlurEffect(
            float radiusX,
            float radiusY,
            @NonNull RenderEffect inputEffect,
            @NonNull TileMode edgeTreatment
    ) {
        long nativeInputEffect = inputEffect != null ? inputEffect.mNativeRenderEffect : 0;
        return new RenderEffect(
                nativeCreateBlurEffect(
                        radiusX,
                        radiusY,
                        nativeInputEffect,
                        edgeTreatment.nativeInt
                )
            );
    }

    /**
     * Create a {@link RenderEffect} that blurs the contents of the
     * {@link android.graphics.RenderNode} that this RenderEffect is installed on with the
     * specified radius along hte x and y axis.
     * @param radiusX Radius of blur along the X axis
     * @param radiusY Radius of blur along the Y axis
     * @param edgeTreatment Policy for how to blur content near edges of the blur kernel
     */
    @NonNull
    public static RenderEffect createBlurEffect(
            float radiusX,
            float radiusY,
            @NonNull TileMode edgeTreatment
    ) {
        return new RenderEffect(
                nativeCreateBlurEffect(
                        radiusX,
                        radiusY,
                        0,
                        edgeTreatment.nativeInt
                )
        );
    }

    private final long mNativeRenderEffect;

    /* only constructed from static factory methods */
    private RenderEffect(long nativeRenderEffect) {
        mNativeRenderEffect = nativeRenderEffect;
        RenderEffectHolder.RENDER_EFFECT_REGISTRY.registerNativeAllocation(
                this, mNativeRenderEffect);
    }

    /**
     * Obtain the pointer to the underlying RenderEffect to be configured
     * on a RenderNode object via {@link RenderNode#setRenderEffect(RenderEffect)}
     */
    /* package */ long getNativeInstance() {
        return mNativeRenderEffect;
    }

    private static native long nativeCreateOffsetEffect(
            float offsetX, float offsetY, long nativeInput);
    private static native long nativeCreateBlurEffect(
            float radiusX, float radiusY, long nativeInput, int edgeTreatment);
    private static native long nativeGetFinalizer();
}
