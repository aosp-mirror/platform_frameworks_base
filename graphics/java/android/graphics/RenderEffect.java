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
import android.graphics.Shader.TileMode;

import libcore.util.NativeAllocationRegistry;

/**
 * Intermediate rendering step used to render drawing commands with a corresponding
 * visual effect. A {@link RenderEffect} can be configured on a {@link RenderNode} through
 * {@link RenderNode#setRenderEffect(RenderEffect)} and will be applied when drawn through
 * {@link Canvas#drawRenderNode(RenderNode)}.
 * Additionally a {@link RenderEffect} can be applied to a View's backing RenderNode through
 * {@link android.view.View#setRenderEffect(RenderEffect)}
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
     * specified radius along the x and y axis.
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

    /**
     * Create a {@link RenderEffect} that renders the contents of the input {@link Bitmap}.
     * This is useful to create an input for other {@link RenderEffect} types such as
     * {@link RenderEffect#createBlurEffect(float, float, RenderEffect, TileMode)} or
     * {@link RenderEffect#createColorFilterEffect(ColorFilter, RenderEffect)}
     *
     * @param bitmap The source bitmap to be rendered by the created {@link RenderEffect}
     */
    @NonNull
    public static RenderEffect createBitmapEffect(@NonNull Bitmap bitmap) {
        float right = bitmap.getWidth();
        float bottom = bitmap.getHeight();
        return new RenderEffect(
                nativeCreateBitmapEffect(
                        bitmap.getNativeInstance(),
                        0f,
                        0f,
                        right,
                        bottom,
                        0f,
                        0f,
                        right,
                        bottom
                )
        );
    }

    /**
     * Create a {@link RenderEffect} that renders the contents of the input {@link Bitmap}.
     * This is useful to create an input for other {@link RenderEffect} types such as
     * {@link RenderEffect#createBlurEffect(float, float, RenderEffect, TileMode)} or
     * {@link RenderEffect#createColorFilterEffect(ColorFilter, RenderEffect)}
     *
     * @param bitmap The source bitmap to be rendered by the created {@link RenderEffect}
     * @param src Optional subset of the bitmap to be part of the rendered output. If null
     *            is provided, the entire bitmap bounds are used.
     * @param dst Bounds of the destination which the bitmap is translated and scaled to be
     *            drawn into within the bounds of the {@link RenderNode} this RenderEffect is
     *            installed on
     */
    @NonNull
    public static RenderEffect createBitmapEffect(
            @NonNull Bitmap bitmap,
            @Nullable Rect src,
            @NonNull Rect dst
    ) {
        long bitmapHandle = bitmap.getNativeInstance();
        int left = src == null ? 0 : src.left;
        int top = src == null ? 0 : src.top;
        int right = src == null ? bitmap.getWidth() : src.right;
        int bottom = src == null ? bitmap.getHeight() : src.bottom;
        return new RenderEffect(
                nativeCreateBitmapEffect(
                        bitmapHandle,
                        left,
                        top,
                        right,
                        bottom,
                        dst.left,
                        dst.top,
                        dst.right,
                        dst.bottom
                )
        );
    }

    /**
     * Create a {@link RenderEffect} that applies the color filter to the provided RenderEffect
     *
     * @param colorFilter ColorFilter applied to the content in the input RenderEffect
     * @param renderEffect Source to be transformed by the specified {@link ColorFilter}
     */
    @NonNull
    public static RenderEffect createColorFilterEffect(
            @NonNull ColorFilter colorFilter,
            @NonNull RenderEffect renderEffect
    ) {
        return new RenderEffect(
                nativeCreateColorFilterEffect(
                    colorFilter.getNativeInstance(),
                    renderEffect.getNativeInstance()
                )
            );
    }

    /**
     * Create a {@link RenderEffect} that applies the color filter to the contents of the
     * {@link android.graphics.RenderNode} that this RenderEffect is installed on
     * @param colorFilter ColorFilter applied to the content in the input RenderEffect
     */
    @NonNull
    public static RenderEffect createColorFilterEffect(@NonNull ColorFilter colorFilter) {
        return new RenderEffect(
                nativeCreateColorFilterEffect(
                        colorFilter.getNativeInstance(),
                        0
                )
        );
    }

    /**
     * Create a {@link RenderEffect} that is a composition of 2 other {@link RenderEffect} instances
     * combined by the specified {@link BlendMode}
     *
     * @param dst The Dst pixels used in blending
     * @param src The Src pixels used in blending
     * @param blendMode The {@link BlendMode} to be used to combine colors from the two
     *                  {@link RenderEffect}s
     */
    @NonNull
    public static RenderEffect createBlendModeEffect(
            @NonNull RenderEffect dst,
            @NonNull RenderEffect src,
            @NonNull BlendMode blendMode
    ) {
        return new RenderEffect(
                nativeCreateBlendModeEffect(
                        dst.getNativeInstance(),
                        src.getNativeInstance(),
                        blendMode.getXfermode().porterDuffMode
                )
        );
    }

    /**
     * Create a {@link RenderEffect} that composes 'inner' with 'outer', such that the results of
     * 'inner' are treated as the source bitmap passed to 'outer', i.e.
     *
     * <pre>
     * {@code
     * result = outer(inner(source))
     * }
     * </pre>
     *
     * Consumers should favor explicit chaining of {@link RenderEffect} instances at creation time
     * rather than using chain effect. Chain effects are useful for situations where the input or
     * output are provided from elsewhere and the input or output {@link RenderEffect} need to be
     * changed.
     *
     * @param outer {@link RenderEffect} that consumes the output of {@param inner} as its input
     * @param inner {@link RenderEffect} that is consumed as input by {@param outer}
     */
    @NonNull
    public static RenderEffect createChainEffect(
            @NonNull RenderEffect outer,
            @NonNull RenderEffect inner
    ) {
        return new RenderEffect(
                nativeCreateChainEffect(
                    outer.getNativeInstance(),
                    inner.getNativeInstance()
                )
            );
    }

    /**
     * Create a {@link RenderEffect} that renders the contents of the input {@link Shader}.
     * This is useful to create an input for other {@link RenderEffect} types such as
     * {@link RenderEffect#createBlurEffect(float, float, RenderEffect, TileMode)}
     * {@link RenderEffect#createBlurEffect(float, float, RenderEffect, TileMode)} or
     * {@link RenderEffect#createColorFilterEffect(ColorFilter, RenderEffect)}.
     */
    @NonNull
    public static RenderEffect createShaderEffect(@NonNull Shader shader) {
        return new RenderEffect(nativeCreateShaderEffect(shader.getNativeInstance()));
    }

    /**
     * Create a {@link RenderEffect} that executes the provided {@link RuntimeShader} and passes
     * the contents of the {@link android.graphics.RenderNode} that this RenderEffect is installed
     * on as an input to the shader.
     * @param shader the runtime shader that will bind the inputShaderName to the RenderEffect input
     * @param uniformShaderName the uniform name defined in the RuntimeShader's program to which
     *                         the contents of the RenderNode will be bound
     */
    @NonNull
    public static RenderEffect createRuntimeShaderEffect(
            @NonNull RuntimeShader shader, @NonNull String uniformShaderName) {
        return new RenderEffect(
                nativeCreateRuntimeShaderEffect(shader.getNativeShaderBuilder(),
                        uniformShaderName));
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
    private static native long nativeCreateBitmapEffect(
            long bitmapHandle, float srcLeft, float srcTop, float srcRight, float srcBottom,
            float dstLeft, float dstTop, float dstRight, float dstBottom);
    private static native long nativeCreateColorFilterEffect(long colorFilter, long nativeInput);
    private static native long nativeCreateBlendModeEffect(long dst, long src, int blendmode);
    private static native long nativeCreateChainEffect(long outer, long inner);
    private static native long nativeCreateShaderEffect(long shader);
    private static native long nativeCreateRuntimeShaderEffect(
            long shaderBuilder, String inputShaderName);
    private static native long nativeGetFinalizer();
}
