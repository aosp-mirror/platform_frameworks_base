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

package android.window;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.IBinder;
import android.util.Log;
import android.view.SurfaceControl;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Handles display and layer captures for the system.
 *
 * @hide
 */
public class ScreenCapture {
    private static final String TAG = "ScreenCapture";

    private static native int nativeCaptureDisplay(DisplayCaptureArgs captureArgs,
            ScreenCaptureListener captureListener);
    private static native int nativeCaptureLayers(LayerCaptureArgs captureArgs,
            ScreenCaptureListener captureListener);

    /**
     * @param captureArgs Arguments about how to take the screenshot
     * @param captureListener A listener to receive the screenshot callback
     * @hide
     */
    public static int captureDisplay(@NonNull DisplayCaptureArgs captureArgs,
            @NonNull ScreenCaptureListener captureListener) {
        return nativeCaptureDisplay(captureArgs, captureListener);
    }

    /**
     * Captures all the surfaces in a display and returns a {@link ScreenshotHardwareBuffer} with
     * the content.
     *
     * @hide
     */
    public static ScreenshotHardwareBuffer captureDisplay(
            DisplayCaptureArgs captureArgs) {
        SyncScreenCaptureListener
                screenCaptureListener = new SyncScreenCaptureListener();

        int status = captureDisplay(captureArgs, screenCaptureListener);
        if (status != 0) {
            return null;
        }

        return screenCaptureListener.waitForScreenshot();
    }

    /**
     * Captures a layer and its children and returns a {@link HardwareBuffer} with the content.
     *
     * @param layer            The root layer to capture.
     * @param sourceCrop       The portion of the root surface to capture; caller may pass in 'new
     *                         Rect()' or null if no cropping is desired. If the root layer does not
     *                         have a buffer or a crop set, then a non-empty source crop must be
     *                         specified.
     * @param frameScale       The desired scale of the returned buffer; the raw
     *                         screen will be scaled up/down.
     *
     * @return Returns a HardwareBuffer that contains the layer capture.
     * @hide
     */
    public static ScreenshotHardwareBuffer captureLayers(SurfaceControl layer, Rect sourceCrop,
            float frameScale) {
        return captureLayers(layer, sourceCrop, frameScale, PixelFormat.RGBA_8888);
    }

    /**
     * Captures a layer and its children and returns a {@link HardwareBuffer} with the content.
     *
     * @param layer            The root layer to capture.
     * @param sourceCrop       The portion of the root surface to capture; caller may pass in 'new
     *                         Rect()' or null if no cropping is desired. If the root layer does not
     *                         have a buffer or a crop set, then a non-empty source crop must be
     *                         specified.
     * @param frameScale       The desired scale of the returned buffer; the raw
     *                         screen will be scaled up/down.
     * @param format           The desired pixel format of the returned buffer.
     *
     * @return Returns a HardwareBuffer that contains the layer capture.
     * @hide
     */
    public static ScreenshotHardwareBuffer captureLayers(@NonNull SurfaceControl layer,
            @Nullable Rect sourceCrop, float frameScale, int format) {
        LayerCaptureArgs captureArgs = new LayerCaptureArgs.Builder(layer)
                .setSourceCrop(sourceCrop)
                .setFrameScale(frameScale)
                .setPixelFormat(format)
                .build();

        return captureLayers(captureArgs);
    }

    /**
     * @hide
     */
    public static ScreenshotHardwareBuffer captureLayers(
            LayerCaptureArgs captureArgs) {
        SyncScreenCaptureListener screenCaptureListener = new SyncScreenCaptureListener();

        int status = captureLayers(captureArgs, screenCaptureListener);
        if (status != 0) {
            return null;
        }

        return screenCaptureListener.waitForScreenshot();
    }

    /**
     * Like {@link #captureLayers(SurfaceControl, Rect, float, int)} but with an array of layer
     * handles to exclude.
     * @hide
     */
    public static ScreenshotHardwareBuffer captureLayersExcluding(SurfaceControl layer,
            Rect sourceCrop, float frameScale, int format, SurfaceControl[] exclude) {
        LayerCaptureArgs captureArgs = new LayerCaptureArgs.Builder(layer)
                .setSourceCrop(sourceCrop)
                .setFrameScale(frameScale)
                .setPixelFormat(format)
                .setExcludeLayers(exclude)
                .build();

        return captureLayers(captureArgs);
    }

    /**
     * @param captureArgs Arguments about how to take the screenshot
     * @param captureListener A listener to receive the screenshot callback
     * @hide
     */
    public static int captureLayers(@NonNull LayerCaptureArgs captureArgs,
            @NonNull ScreenCaptureListener captureListener) {
        return nativeCaptureLayers(captureArgs, captureListener);
    }

    /**
     * @hide
     */
    public interface ScreenCaptureListener {
        /**
         * The callback invoked when the screen capture is complete.
         * @param hardwareBuffer Data containing info about the screen capture.
         */
        void onScreenCaptureComplete(ScreenshotHardwareBuffer hardwareBuffer);
    }

    /**
     * A wrapper around HardwareBuffer that contains extra information about how to
     * interpret the screenshot HardwareBuffer.
     *
     * @hide
     */
    public static class ScreenshotHardwareBuffer {
        private final HardwareBuffer mHardwareBuffer;
        private final ColorSpace mColorSpace;
        private final boolean mContainsSecureLayers;
        private final boolean mContainsHdrLayers;

        public ScreenshotHardwareBuffer(HardwareBuffer hardwareBuffer, ColorSpace colorSpace,
                boolean containsSecureLayers, boolean containsHdrLayers) {
            mHardwareBuffer = hardwareBuffer;
            mColorSpace = colorSpace;
            mContainsSecureLayers = containsSecureLayers;
            mContainsHdrLayers = containsHdrLayers;
        }

       /**
        * Create ScreenshotHardwareBuffer from an existing HardwareBuffer object.
        * @param hardwareBuffer The existing HardwareBuffer object
        * @param namedColorSpace Integer value of a named color space {@link ColorSpace.Named}
        * @param containsSecureLayers Indicates whether this graphic buffer contains captured
        *                             contents of secure layers, in which case the screenshot
        *                             should not be persisted.
        * @param containsHdrLayers Indicates whether this graphic buffer contains HDR content.
        */
        private static ScreenshotHardwareBuffer createFromNative(HardwareBuffer hardwareBuffer,
                int namedColorSpace, boolean containsSecureLayers, boolean containsHdrLayers) {
            ColorSpace colorSpace = ColorSpace.get(ColorSpace.Named.values()[namedColorSpace]);
            return new ScreenshotHardwareBuffer(
                    hardwareBuffer, colorSpace, containsSecureLayers, containsHdrLayers);
        }

        public ColorSpace getColorSpace() {
            return mColorSpace;
        }

        public HardwareBuffer getHardwareBuffer() {
            return mHardwareBuffer;
        }

        /**
         * Whether this screenshot contains secure layers
         */
        public boolean containsSecureLayers() {
            return mContainsSecureLayers;
        }
        /**
         * Returns whether the screenshot contains at least one HDR layer.
         * This information may be useful for informing the display whether this screenshot
         * is allowed to be dimmed to SDR white.
         */
        public boolean containsHdrLayers() {
            return mContainsHdrLayers;
        }

        /**
         * Copy content of ScreenshotHardwareBuffer into a hardware bitmap and return it.
         * Note: If you want to modify the Bitmap in software, you will need to copy the Bitmap
         * into
         * a software Bitmap using {@link Bitmap#copy(Bitmap.Config, boolean)}
         *
         * CAVEAT: This can be extremely slow; avoid use unless absolutely necessary; prefer to
         * directly
         * use the {@link HardwareBuffer} directly.
         *
         * @return Bitmap generated from the {@link HardwareBuffer}
         */
        public Bitmap asBitmap() {
            if (mHardwareBuffer == null) {
                Log.w(TAG, "Failed to take screenshot. Null screenshot object");
                return null;
            }
            return Bitmap.wrapHardwareBuffer(mHardwareBuffer, mColorSpace);
        }
    }

    private static class SyncScreenCaptureListener implements ScreenCaptureListener {
        private static final int SCREENSHOT_WAIT_TIME_S = 1;
        private ScreenshotHardwareBuffer mScreenshotHardwareBuffer;
        private final CountDownLatch mCountDownLatch = new CountDownLatch(1);

        @Override
        public void onScreenCaptureComplete(ScreenshotHardwareBuffer hardwareBuffer) {
            mScreenshotHardwareBuffer = hardwareBuffer;
            mCountDownLatch.countDown();
        }

        private ScreenshotHardwareBuffer waitForScreenshot() {
            try {
                mCountDownLatch.await(SCREENSHOT_WAIT_TIME_S, TimeUnit.SECONDS);
            } catch (Exception e) {
                Log.e(TAG, "Failed to wait for screen capture result", e);
            }

            return mScreenshotHardwareBuffer;
        }
    }

    /**
     * A common arguments class used for various screenshot requests. This contains arguments that
     * are shared between {@link DisplayCaptureArgs} and {@link LayerCaptureArgs}
     * @hide
     */
    private abstract static class CaptureArgs {
        private final int mPixelFormat;
        private final Rect mSourceCrop = new Rect();
        private final float mFrameScaleX;
        private final float mFrameScaleY;
        private final boolean mCaptureSecureLayers;
        private final boolean mAllowProtected;
        private final long mUid;
        private final boolean mGrayscale;

        private CaptureArgs(Builder<? extends Builder<?>> builder) {
            mPixelFormat = builder.mPixelFormat;
            mSourceCrop.set(builder.mSourceCrop);
            mFrameScaleX = builder.mFrameScaleX;
            mFrameScaleY = builder.mFrameScaleY;
            mCaptureSecureLayers = builder.mCaptureSecureLayers;
            mAllowProtected = builder.mAllowProtected;
            mUid = builder.mUid;
            mGrayscale = builder.mGrayscale;
        }

        /**
         * The Builder class used to construct {@link CaptureArgs}
         *
         * @param <T> A builder that extends {@link Builder}
         */
        abstract static class Builder<T extends Builder<T>> {
            private int mPixelFormat = PixelFormat.RGBA_8888;
            private final Rect mSourceCrop = new Rect();
            private float mFrameScaleX = 1;
            private float mFrameScaleY = 1;
            private boolean mCaptureSecureLayers;
            private boolean mAllowProtected;
            private long mUid = -1;
            private boolean mGrayscale;

            /**
             * The desired pixel format of the returned buffer.
             */
            public T setPixelFormat(int pixelFormat) {
                mPixelFormat = pixelFormat;
                return getThis();
            }

            /**
             * The portion of the screen to capture into the buffer. Caller may pass  in
             * 'new Rect()' or null if no cropping is desired.
             */
            public T setSourceCrop(@Nullable Rect sourceCrop) {
                if (sourceCrop == null) {
                    mSourceCrop.setEmpty();
                } else {
                    mSourceCrop.set(sourceCrop);
                }
                return getThis();
            }

            /**
             * The desired scale of the returned buffer. The raw screen will be scaled up/down.
             */
            public T setFrameScale(float frameScale) {
                mFrameScaleX = frameScale;
                mFrameScaleY = frameScale;
                return getThis();
            }

            /**
             * The desired scale of the returned buffer, allowing separate values for x and y scale.
             * The raw screen will be scaled up/down.
             */
            public T setFrameScale(float frameScaleX, float frameScaleY) {
                mFrameScaleX = frameScaleX;
                mFrameScaleY = frameScaleY;
                return getThis();
            }

            /**
             * Whether to allow the screenshot of secure layers. Warning: This should only be done
             * if the content will be placed in a secure SurfaceControl.
             *
             * @see ScreenshotHardwareBuffer#containsSecureLayers()
             */
            public T setCaptureSecureLayers(boolean captureSecureLayers) {
                mCaptureSecureLayers = captureSecureLayers;
                return getThis();
            }

            /**
             * Whether to allow the screenshot of protected (DRM) content. Warning: The screenshot
             * cannot be read in unprotected space.
             *
             * @see HardwareBuffer#USAGE_PROTECTED_CONTENT
             */
            public T setAllowProtected(boolean allowProtected) {
                mAllowProtected = allowProtected;
                return getThis();
            }

            /**
             * Set the uid of the content that should be screenshot. The code will skip any surfaces
             * that don't belong to the specified uid.
             */
            public T setUid(long uid) {
                mUid = uid;
                return getThis();
            }

            /**
             * Set whether the screenshot should use grayscale or not.
             */
            public T setGrayscale(boolean grayscale) {
                mGrayscale = grayscale;
                return getThis();
            }

            /**
             * Each sub class should return itself to allow the builder to chain properly
             */
            abstract T getThis();
        }
    }

    /**
     * The arguments class used to make display capture requests.
     *
     * @see #nativeCaptureDisplay(DisplayCaptureArgs, ScreenCaptureListener)
     * @hide
     */
    public static class DisplayCaptureArgs extends CaptureArgs {
        private final IBinder mDisplayToken;
        private final int mWidth;
        private final int mHeight;
        private final boolean mUseIdentityTransform;

        private DisplayCaptureArgs(Builder builder) {
            super(builder);
            mDisplayToken = builder.mDisplayToken;
            mWidth = builder.mWidth;
            mHeight = builder.mHeight;
            mUseIdentityTransform = builder.mUseIdentityTransform;
        }

        /**
         * The Builder class used to construct {@link DisplayCaptureArgs}
         */
        public static class Builder extends CaptureArgs.Builder<Builder> {
            private IBinder mDisplayToken;
            private int mWidth;
            private int mHeight;
            private boolean mUseIdentityTransform;

            /**
             * Construct a new {@link LayerCaptureArgs} with the set parameters. The builder
             * remains valid.
             */
            public DisplayCaptureArgs build() {
                if (mDisplayToken == null) {
                    throw new IllegalStateException(
                            "Can't take screenshot with null display token");
                }
                return new DisplayCaptureArgs(this);
            }

            public Builder(IBinder displayToken) {
                setDisplayToken(displayToken);
            }

            /**
             * The display to take the screenshot of.
             */
            public Builder setDisplayToken(IBinder displayToken) {
                mDisplayToken = displayToken;
                return this;
            }

            /**
             * Set the desired size of the returned buffer. The raw screen  will be  scaled down to
             * this size
             *
             * @param width  The desired width of the returned buffer. Caller may pass in 0 if no
             *               scaling is desired.
             * @param height The desired height of the returned buffer. Caller may pass in 0 if no
             *               scaling is desired.
             */
            public Builder setSize(int width, int height) {
                mWidth = width;
                mHeight = height;
                return this;
            }

            /**
             * Replace the rotation transform of the display with the identity transformation while
             * taking the screenshot. This ensures the screenshot is taken in the ROTATION_0
             * orientation. Set this value to false if the screenshot should be taken in the
             * current screen orientation.
             */
            public Builder setUseIdentityTransform(boolean useIdentityTransform) {
                mUseIdentityTransform = useIdentityTransform;
                return this;
            }

            @Override
            Builder getThis() {
                return this;
            }
        }
    }

    /**
     * The arguments class used to make layer capture requests.
     *
     * @see #nativeCaptureLayers(LayerCaptureArgs, ScreenCaptureListener)
     * @hide
     */
    public static class LayerCaptureArgs extends CaptureArgs {
        private final long mNativeLayer;
        private final long[] mNativeExcludeLayers;
        private final boolean mChildrenOnly;

        private LayerCaptureArgs(Builder builder) {
            super(builder);
            mChildrenOnly = builder.mChildrenOnly;
            mNativeLayer = builder.mLayer.mNativeObject;
            if (builder.mExcludeLayers != null) {
                mNativeExcludeLayers = new long[builder.mExcludeLayers.length];
                for (int i = 0; i < builder.mExcludeLayers.length; i++) {
                    mNativeExcludeLayers[i] = builder.mExcludeLayers[i].mNativeObject;
                }
            } else {
                mNativeExcludeLayers = null;
            }
        }

        /**
         * The Builder class used to construct {@link LayerCaptureArgs}
         */
        public static class Builder extends CaptureArgs.Builder<Builder> {
            private SurfaceControl mLayer;
            private SurfaceControl[] mExcludeLayers;
            private boolean mChildrenOnly = true;

            /**
             * Construct a new {@link LayerCaptureArgs} with the set parameters. The builder
             * remains valid.
             */
            public LayerCaptureArgs build() {
                if (mLayer == null) {
                    throw new IllegalStateException(
                            "Can't take screenshot with null layer");
                }
                return new LayerCaptureArgs(this);
            }

            public Builder(SurfaceControl layer) {
                setLayer(layer);
            }

            /**
             * The root layer to capture.
             */
            public Builder setLayer(SurfaceControl layer) {
                mLayer = layer;
                return this;
            }


            /**
             * An array of layer handles to exclude.
             */
            public Builder setExcludeLayers(@Nullable SurfaceControl[] excludeLayers) {
                mExcludeLayers = excludeLayers;
                return this;
            }

            /**
             * Whether to include the layer itself in the screenshot or just the children and their
             * descendants.
             */
            public Builder setChildrenOnly(boolean childrenOnly) {
                mChildrenOnly = childrenOnly;
                return this;
            }

            @Override
            Builder getThis() {
                return this;
            }

        }
    }

}
