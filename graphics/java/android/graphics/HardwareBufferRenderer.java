/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.FloatRange;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.ColorSpace.Named;
import android.hardware.HardwareBuffer;
import android.hardware.SyncFence;
import android.view.SurfaceControl;

import libcore.util.NativeAllocationRegistry;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * <p>Creates an instance of a hardware-accelerated renderer. This is used to render a scene built
 * from {@link RenderNode}s to an output {@link HardwareBuffer}. There can be as many
 * HardwareBufferRenderer instances as desired.</p>
 *
 * <h3>Resources & lifecycle</h3>
 *
 * <p>All HardwareBufferRenderer and {@link HardwareRenderer} instances share a common render
 * thread. Therefore HardwareBufferRenderer will share common resources and GPU utilization with
 * hardware accelerated rendering initiated by the UI thread of an application.
 * The render thread contains the GPU context & resources necessary to do GPU-accelerated
 * rendering. As such, the first HardwareBufferRenderer created comes with the cost of also creating
 * the associated GPU contexts, however each incremental HardwareBufferRenderer thereafter is fairly
 * cheap. The expected usage is to have a HardwareBufferRenderer instance for every active {@link
 * HardwareBuffer}.</p>
 *
 * This is useful in situations where a scene built with {@link RenderNode}s can be consumed
 * directly by the system compositor through
 * {@link SurfaceControl.Transaction#setBuffer(SurfaceControl, HardwareBuffer)}.
 *
 * HardwareBufferRenderer will never clear contents before each draw invocation so previous contents
 * in the {@link HardwareBuffer} target will be preserved across renders.
 */
public class HardwareBufferRenderer implements AutoCloseable {

    private static final ColorSpace DEFAULT_COLORSPACE = ColorSpace.get(Named.SRGB);

    private static class HardwareBufferRendererHolder {
        public static final NativeAllocationRegistry REGISTRY =
                NativeAllocationRegistry.createMalloced(
                    HardwareBufferRenderer.class.getClassLoader(), nGetFinalizer());
    }

    private final HardwareBuffer mHardwareBuffer;
    private final RenderRequest mRenderRequest;
    private final RenderNode mRootNode;
    private final Runnable mCleaner;

    private long mProxy;

    /**
     * Creates a new instance of {@link HardwareBufferRenderer} with the provided {@link
     * HardwareBuffer} as the output of the rendered scene.
     */
    public HardwareBufferRenderer(@NonNull HardwareBuffer buffer) {
        RenderNode rootNode = RenderNode.adopt(nCreateRootRenderNode());
        rootNode.setClipToBounds(false);
        mProxy = nCreateHardwareBufferRenderer(buffer, rootNode.mNativeRenderNode);
        mCleaner = HardwareBufferRendererHolder.REGISTRY.registerNativeAllocation(this, mProxy);
        mRenderRequest = new RenderRequest();
        mRootNode = rootNode;
        mHardwareBuffer = buffer;
    }

    /**
     * Sets the content root to render. It is not necessary to call this whenever the content
     * recording changes. Any mutations to the RenderNode content, or any of the RenderNodes
     * contained within the content node, will be applied whenever a new {@link RenderRequest} is
     * issued via {@link #obtainRenderRequest()} and {@link RenderRequest#draw(Executor,
     * Consumer)}.
     *
     * @param content The content to set as the root RenderNode. If null the content root is removed
     * and the renderer will draw nothing.
     */
    public void setContentRoot(@Nullable RenderNode content) {
        RecordingCanvas canvas = mRootNode.beginRecording();
        if (content != null) {
            canvas.drawRenderNode(content);
        }
        mRootNode.endRecording();
    }

    /**
     * Returns a {@link RenderRequest} that can be used to render into the provided {@link
     * HardwareBuffer}. This is used to synchronize the RenderNode content provided by {@link
     * #setContentRoot(RenderNode)}.
     *
     * @return An instance of {@link RenderRequest}. The instance may be reused for every frame, so
     * the caller should not hold onto it for longer than a single render request.
     */
    @NonNull
    public RenderRequest obtainRenderRequest() {
        mRenderRequest.reset();
        return mRenderRequest;
    }

    /**
     * Returns if the {@link HardwareBufferRenderer} has already been closed. That is
     * {@link HardwareBufferRenderer#close()} has been invoked.
     * @return True if the {@link HardwareBufferRenderer} has been closed, false otherwise.
     */
    public boolean isClosed() {
        return mProxy == 0L;
    }

    /**
     * Releases the resources associated with this {@link HardwareBufferRenderer} instance. **Note**
     * this does not call {@link HardwareBuffer#close()} on the provided {@link HardwareBuffer}
     * instance
     */
    @Override
    public void close() {
        // Note we explicitly call this only here to clean-up potential animator state
        // This is not done as part of the NativeAllocationRegistry as it would invoke animator
        // callbacks on the wrong thread
        nDestroyRootRenderNode(mRootNode.mNativeRenderNode);
        if (mProxy != 0L) {
            mCleaner.run();
            mProxy = 0L;
        }
    }

    /**
     * Sets the center of the light source. The light source point controls the directionality and
     * shape of shadows rendered by RenderNode Z & elevation.
     *
     * <p>The light source should be setup both as part of initial configuration, and whenever
     * the window moves to ensure the light source stays anchored in display space instead of in
     * window space.
     *
     * <p>This must be set at least once along with {@link #setLightSourceAlpha(float, float)}
     * before shadows will work.
     *
     * @param lightX The X position of the light source. If unsure, a reasonable default
     * is 'displayWidth / 2f - windowLeft'.
     * @param lightY The Y position of the light source. If unsure, a reasonable default
     * is '0 - windowTop'
     * @param lightZ The Z position of the light source. Must be >= 0. If unsure, a reasonable
     * default is 600dp.
     * @param lightRadius The radius of the light source. Smaller radius will have sharper edges,
     * larger radius will have softer shadows. If unsure, a reasonable default is 800 dp.
     */
    public void setLightSourceGeometry(
            float lightX,
            float lightY,
            @FloatRange(from = 0f) float lightZ,
            @FloatRange(from = 0f) float lightRadius
    ) {
        validateFinite(lightX, "lightX");
        validateFinite(lightY, "lightY");
        validatePositive(lightZ, "lightZ");
        validatePositive(lightRadius, "lightRadius");
        nSetLightGeometry(mProxy, lightX, lightY, lightZ, lightRadius);
    }

    /**
     * Configures the ambient & spot shadow alphas. This is the alpha used when the shadow has max
     * alpha, and ramps down from the values provided to zero.
     *
     * <p>These values are typically provided by the current theme, see
     * {@link android.R.attr#spotShadowAlpha} and {@link android.R.attr#ambientShadowAlpha}.
     *
     * <p>This must be set at least once along with
     * {@link #setLightSourceGeometry(float, float, float, float)} before shadows will work.
     *
     * @param ambientShadowAlpha The alpha for the ambient shadow. If unsure, a reasonable default
     * is 0.039f.
     * @param spotShadowAlpha The alpha for the spot shadow. If unsure, a reasonable default is
     * 0.19f.
     */
    public void setLightSourceAlpha(@FloatRange(from = 0.0f, to = 1.0f) float ambientShadowAlpha,
            @FloatRange(from = 0.0f, to = 1.0f) float spotShadowAlpha) {
        validateAlpha(ambientShadowAlpha, "ambientShadowAlpha");
        validateAlpha(spotShadowAlpha, "spotShadowAlpha");
        nSetLightAlpha(mProxy, ambientShadowAlpha, spotShadowAlpha);
    }

    /**
     * Class that contains data regarding the result of the render request.
     * Consumers are to wait on the provided {@link SyncFence} before consuming the HardwareBuffer
     * provided to {@link HardwareBufferRenderer} as well as verify that the status returned by
     * {@link RenderResult#getStatus()} returns {@link RenderResult#SUCCESS}.
     */
    public static final class RenderResult {

        /**
         * Render request was completed successfully
         */
        public static final int SUCCESS = 0;

        /**
         * Render request failed with an unknown error
         */
        public static final int ERROR_UNKNOWN = 1;

        /** @hide **/
        @IntDef(value = {SUCCESS, ERROR_UNKNOWN})
        @Retention(RetentionPolicy.SOURCE)
        public @interface RenderResultStatus{}

        private final SyncFence mFence;
        private final int mResultStatus;

        private RenderResult(@NonNull SyncFence fence, @RenderResultStatus int resultStatus) {
            mFence = fence;
            mResultStatus = resultStatus;
        }

        @NonNull
        public SyncFence getFence() {
            return mFence;
        }

        @RenderResultStatus
        public int getStatus() {
            return mResultStatus;
        }
    }

    /**
     * Sets the parameters that can be used to control a render request for a {@link
     * HardwareBufferRenderer}. This is not thread-safe and must not be held on to for longer than a
     * single request.
     */
    public final class RenderRequest {

        private ColorSpace mColorSpace = DEFAULT_COLORSPACE;
        private int mTransform = SurfaceControl.BUFFER_TRANSFORM_IDENTITY;

        private RenderRequest() { }

        /**
         * Syncs the RenderNode tree to the render thread and requests content to be drawn. This
         * {@link RenderRequest} instance should no longer be used after calling this method. The
         * system internally may reuse instances of {@link RenderRequest} to reduce allocation
         * churn.
         *
         * @param executor Executor used to deliver callbacks
         * @param renderCallback Callback invoked when rendering is complete. This includes a
         * {@link RenderResult} that provides a {@link SyncFence} that should be waited upon for
         * completion before consuming the rendered output in the provided {@link HardwareBuffer}
         * instance.
         *
         * @throws IllegalStateException if attempt to draw is made when
         * {@link HardwareBufferRenderer#isClosed()} returns true
         */
        public void draw(
                @NonNull Executor executor,
                @NonNull Consumer<RenderResult> renderCallback
        ) {
            Consumer<RenderResult> wrapped = consumable -> executor.execute(
                    () -> renderCallback.accept(consumable));
            if (!isClosed()) {
                nRender(
                        mProxy,
                        mTransform,
                        mHardwareBuffer.getWidth(),
                        mHardwareBuffer.getHeight(),
                        mColorSpace.getNativeInstance(),
                        wrapped);
            } else {
                throw new IllegalStateException("Attempt to draw with a HardwareBufferRenderer "
                    + "instance that has already been closed");
            }
        }

        private void reset() {
            mColorSpace = DEFAULT_COLORSPACE;
            mTransform = SurfaceControl.BUFFER_TRANSFORM_IDENTITY;
        }

        /**
         * Configures the color space which the content should be rendered in. This affects
         * how the framework will interpret the color at each pixel. The color space provided here
         * must be non-null, RGB based and leverage an ICC parametric curve. The min/max values
         * of the components should not reduce the numerical range compared to the previously
         * assigned color space. If left unspecified, the default color space of SRGB will be used.
         *
         * @param colorSpace The color space the content should be rendered in. If null is provided
         * the default of SRGB will be used.
         */
        @NonNull
        public RenderRequest setColorSpace(@Nullable ColorSpace colorSpace) {
            if (colorSpace == null) {
                mColorSpace = DEFAULT_COLORSPACE;
            } else {
                mColorSpace = colorSpace;
            }
            return this;
        }

        /**
         * Specifies a transform to be applied before content is rendered. This is useful
         * for pre-rotating content for the current display orientation to increase performance
         * of displaying the associated buffer. This transformation will also adjust the light
         * source position for the specified rotation.
         * @see SurfaceControl.Transaction#setBufferTransform(SurfaceControl, int)
         */
        @NonNull
        public RenderRequest setBufferTransform(
                @SurfaceControl.BufferTransform int bufferTransform) {
            boolean validTransform = bufferTransform == SurfaceControl.BUFFER_TRANSFORM_IDENTITY
                    || bufferTransform == SurfaceControl.BUFFER_TRANSFORM_ROTATE_90
                    || bufferTransform == SurfaceControl.BUFFER_TRANSFORM_ROTATE_180
                    || bufferTransform == SurfaceControl.BUFFER_TRANSFORM_ROTATE_270;
            if (validTransform) {
                mTransform = bufferTransform;
            } else {
                throw new IllegalArgumentException("Invalid transform provided, must be one of"
                    + "the SurfaceControl.BufferTransform values");
            }
            return this;
        }
    }

    /**
     * @hide
     */
    /* package */
    static native int nRender(long renderer, int transform, int width, int height, long colorSpace,
            Consumer<RenderResult> callback);

    private static native long nCreateRootRenderNode();

    private static native void nDestroyRootRenderNode(long rootRenderNode);

    private static native long nCreateHardwareBufferRenderer(HardwareBuffer buffer,
            long rootRenderNode);

    private static native void nSetLightGeometry(long bufferRenderer, float lightX, float lightY,
            float lightZ, float radius);

    private static native void nSetLightAlpha(long nativeProxy, float ambientShadowAlpha,
            float spotShadowAlpha);

    private static native long nGetFinalizer();

    // Called by native
    private static void invokeRenderCallback(
            @NonNull Consumer<RenderResult> callback,
            int fd,
            int status
    ) {
        callback.accept(new RenderResult(SyncFence.adopt(fd), status));
    }

    private static void validateAlpha(float alpha, String argumentName) {
        if (!(alpha >= 0.0f && alpha <= 1.0f)) {
            throw new IllegalArgumentException(argumentName + " must be a valid alpha, "
                + alpha + " is not in the range of 0.0f to 1.0f");
        }
    }

    private static void validateFinite(float f, String argumentName) {
        if (!Float.isFinite(f)) {
            throw new IllegalArgumentException(argumentName + " must be finite, given=" + f);
        }
    }

    private static void validatePositive(float f, String argumentName) {
        if (!(Float.isFinite(f) && f >= 0.0f)) {
            throw new IllegalArgumentException(argumentName
                + " must be a finite positive, given=" + f);
        }
    }
}
