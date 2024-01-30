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

package android.view;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.HardwareRenderer;
import android.graphics.Rect;
import android.os.Handler;
import android.view.ViewTreeObserver.OnDrawListener;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Provides a mechanisms to issue pixel copy requests to allow for copy
 * operations from {@link Surface} to {@link Bitmap}
 */
public final class PixelCopy {

    /** @hide */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({SUCCESS, ERROR_UNKNOWN, ERROR_TIMEOUT, ERROR_SOURCE_NO_DATA,
        ERROR_SOURCE_INVALID, ERROR_DESTINATION_INVALID})
    public @interface CopyResultStatus {}

    /** The pixel copy request succeeded */
    public static final int SUCCESS = 0;

    /** The pixel copy request failed with an unknown error. */
    public static final int ERROR_UNKNOWN = 1;

    /**
     * A timeout occurred while trying to acquire a buffer from the source to
     * copy from.
     */
    public static final int ERROR_TIMEOUT = 2;

    /**
     * The source has nothing to copy from. When the source is a {@link Surface}
     * this means that no buffers have been queued yet. Wait for the source
     * to produce a frame and try again.
     */
    public static final int ERROR_SOURCE_NO_DATA = 3;

    /**
     * It is not possible to copy from the source. This can happen if the source
     * is hardware-protected or destroyed.
     */
    public static final int ERROR_SOURCE_INVALID = 4;

    /**
     * The destination isn't a valid copy target. If the destination is a bitmap
     * this can occur if the bitmap is too large for the hardware to copy to.
     * It can also occur if the destination has been destroyed.
     */
    public static final int ERROR_DESTINATION_INVALID = 5;

    /**
     * Listener for observing the completion of a PixelCopy request.
     */
    public interface OnPixelCopyFinishedListener {
        /**
         * Callback for when a pixel copy request has completed. This will be called
         * regardless of whether the copy succeeded or failed.
         *
         * @param copyResult Contains the resulting status of the copy request.
         * This will either be {@link PixelCopy#SUCCESS} or one of the
         * <code>PixelCopy.ERROR_*</code> values.
         */
        void onPixelCopyFinished(@CopyResultStatus int copyResult);
    }

    /**
     * Requests for the display content of a {@link SurfaceView} to be copied
     * into a provided {@link Bitmap}.
     *
     * The contents of the source will be scaled to fit exactly inside the bitmap.
     * The pixel format of the source buffer will be converted, as part of the copy,
     * to fit the the bitmap's {@link Bitmap.Config}. The most recently queued buffer
     * in the SurfaceView's Surface will be used as the source of the copy.
     *
     * @param source The source from which to copy
     * @param dest The destination of the copy. The source will be scaled to
     * match the width, height, and format of this bitmap.
     * @param listener Callback for when the pixel copy request completes
     * @param listenerThread The callback will be invoked on this Handler when
     * the copy is finished.
     */
    public static void request(@NonNull SurfaceView source, @NonNull Bitmap dest,
            @NonNull OnPixelCopyFinishedListener listener, @NonNull Handler listenerThread) {
        request(source.getHolder().getSurface(), dest, listener, listenerThread);
    }

    /**
     * Requests for the display content of a {@link SurfaceView} to be copied
     * into a provided {@link Bitmap}.
     *
     * The contents of the source will be scaled to fit exactly inside the bitmap.
     * The pixel format of the source buffer will be converted, as part of the copy,
     * to fit the the bitmap's {@link Bitmap.Config}. The most recently queued buffer
     * in the SurfaceView's Surface will be used as the source of the copy.
     *
     * @param source The source from which to copy
     * @param srcRect The area of the source to copy from. If this is null
     * the copy area will be the entire surface. The rect will be clamped to
     * the bounds of the Surface.
     * @param dest The destination of the copy. The source will be scaled to
     * match the width, height, and format of this bitmap.
     * @param listener Callback for when the pixel copy request completes
     * @param listenerThread The callback will be invoked on this Handler when
     * the copy is finished.
     */
    public static void request(@NonNull SurfaceView source, @Nullable Rect srcRect,
            @NonNull Bitmap dest, @NonNull OnPixelCopyFinishedListener listener,
            @NonNull Handler listenerThread) {
        request(source.getHolder().getSurface(), srcRect,
                dest, listener, listenerThread);
    }

    /**
     * Requests a copy of the pixels from a {@link Surface} to be copied into
     * a provided {@link Bitmap}.
     *
     * The contents of the source will be scaled to fit exactly inside the bitmap.
     * The pixel format of the source buffer will be converted, as part of the copy,
     * to fit the the bitmap's {@link Bitmap.Config}. The most recently queued buffer
     * in the Surface will be used as the source of the copy.
     *
     * @param source The source from which to copy
     * @param dest The destination of the copy. The source will be scaled to
     * match the width, height, and format of this bitmap.
     * @param listener Callback for when the pixel copy request completes
     * @param listenerThread The callback will be invoked on this Handler when
     * the copy is finished.
     */
    public static void request(@NonNull Surface source, @NonNull Bitmap dest,
            @NonNull OnPixelCopyFinishedListener listener, @NonNull Handler listenerThread) {
        request(source, null, dest, listener, listenerThread);
    }

    /**
     * Requests a copy of the pixels at the provided {@link Rect} from
     * a {@link Surface} to be copied into a provided {@link Bitmap}.
     *
     * The contents of the source rect will be scaled to fit exactly inside the bitmap.
     * The pixel format of the source buffer will be converted, as part of the copy,
     * to fit the the bitmap's {@link Bitmap.Config}. The most recently queued buffer
     * in the Surface will be used as the source of the copy.
     *
     * @param source The source from which to copy
     * @param srcRect The area of the source to copy from. If this is null
     * the copy area will be the entire surface. The rect will be clamped to
     * the bounds of the Surface.
     * @param dest The destination of the copy. The source will be scaled to
     * match the width, height, and format of this bitmap.
     * @param listener Callback for when the pixel copy request completes
     * @param listenerThread The callback will be invoked on this Handler when
     * the copy is finished.
     */
    public static void request(@NonNull Surface source, @Nullable Rect srcRect,
            @NonNull Bitmap dest, @NonNull OnPixelCopyFinishedListener listener,
            @NonNull Handler listenerThread) {
        validateBitmapDest(dest);
        if (!source.isValid()) {
            throw new IllegalArgumentException("Surface isn't valid, source.isValid() == false");
        }
        if (srcRect != null && srcRect.isEmpty()) {
            throw new IllegalArgumentException("sourceRect is empty");
        }
        HardwareRenderer.copySurfaceInto(source, new HardwareRenderer.CopyRequest(srcRect, dest) {
            @Override
            public void onCopyFinished(int result) {
                listenerThread.post(() -> listener.onPixelCopyFinished(result));
            }
        });
    }

    /**
     * Requests a copy of the pixels from a {@link Window} to be copied into
     * a provided {@link Bitmap}.
     *
     * The contents of the source will be scaled to fit exactly inside the bitmap.
     * The pixel format of the source buffer will be converted, as part of the copy,
     * to fit the the bitmap's {@link Bitmap.Config}. The most recently queued buffer
     * in the Window's Surface will be used as the source of the copy.
     *
     * Note: This is limited to being able to copy from Window's with a non-null
     * DecorView. If {@link Window#peekDecorView()} is null this throws an
     * {@link IllegalArgumentException}. It will similarly throw an exception
     * if the DecorView has not yet acquired a backing surface. It is recommended
     * that {@link OnDrawListener} is used to ensure that at least one draw
     * has happened before trying to copy from the window, otherwise either
     * an {@link IllegalArgumentException} will be thrown or an error will
     * be returned to the {@link OnPixelCopyFinishedListener}.
     *
     * @param source The source from which to copy
     * @param dest The destination of the copy. The source will be scaled to
     * match the width, height, and format of this bitmap.
     * @param listener Callback for when the pixel copy request completes
     * @param listenerThread The callback will be invoked on this Handler when
     * the copy is finished.
     */
    public static void request(@NonNull Window source, @NonNull Bitmap dest,
            @NonNull OnPixelCopyFinishedListener listener, @NonNull Handler listenerThread) {
        request(source, null, dest, listener, listenerThread);
    }

    /**
     * Requests a copy of the pixels at the provided {@link Rect} from
     * a {@link Window} to be copied into a provided {@link Bitmap}.
     *
     * The contents of the source rect will be scaled to fit exactly inside the bitmap.
     * The pixel format of the source buffer will be converted, as part of the copy,
     * to fit the the bitmap's {@link Bitmap.Config}. The most recently queued buffer
     * in the Window's Surface will be used as the source of the copy.
     *
     * Note: This is limited to being able to copy from Window's with a non-null
     * DecorView. If {@link Window#peekDecorView()} is null this throws an
     * {@link IllegalArgumentException}. It will similarly throw an exception
     * if the DecorView has not yet acquired a backing surface. It is recommended
     * that {@link OnDrawListener} is used to ensure that at least one draw
     * has happened before trying to copy from the window, otherwise either
     * an {@link IllegalArgumentException} will be thrown or an error will
     * be returned to the {@link OnPixelCopyFinishedListener}.
     *
     * @param source The source from which to copy
     * @param srcRect The area of the source to copy from. If this is null
     * the copy area will be the entire surface. The rect will be clamped to
     * the bounds of the Surface.
     * @param dest The destination of the copy. The source will be scaled to
     * match the width, height, and format of this bitmap.
     * @param listener Callback for when the pixel copy request completes
     * @param listenerThread The callback will be invoked on this Handler when
     * the copy is finished.
     */
    public static void request(@NonNull Window source, @Nullable Rect srcRect,
            @NonNull Bitmap dest, @NonNull OnPixelCopyFinishedListener listener,
            @NonNull Handler listenerThread) {
        validateBitmapDest(dest);
        final Rect insets = new Rect();
        final Surface surface = sourceForWindow(source, insets);
        request(surface, adjustSourceRectForInsets(insets, srcRect), dest, listener,
                listenerThread);
    }

    private static void validateBitmapDest(Bitmap bitmap) {
        // TODO: Pre-check max texture dimens if we can
        if (bitmap == null) {
            throw new IllegalArgumentException("Bitmap cannot be null");
        }
        if (bitmap.isRecycled()) {
            throw new IllegalArgumentException("Bitmap is recycled");
        }
        if (!bitmap.isMutable()) {
            throw new IllegalArgumentException("Bitmap is immutable");
        }
    }

    private static Surface sourceForWindow(Window source, Rect outInsets) {
        if (source == null) {
            throw new IllegalArgumentException("source is null");
        }
        if (source.peekDecorView() == null) {
            throw new IllegalArgumentException(
                    "Only able to copy windows with decor views");
        }
        Surface surface = null;
        final ViewRootImpl root = source.peekDecorView().getViewRootImpl();
        if (root != null) {
            surface = root.mSurface;
            final Rect surfaceInsets = root.mWindowAttributes.surfaceInsets;
            outInsets.set(surfaceInsets.left, surfaceInsets.top,
                    root.mWidth + surfaceInsets.left, root.mHeight + surfaceInsets.top);
        }
        if (surface == null || !surface.isValid()) {
            throw new IllegalArgumentException(
                    "Window doesn't have a backing surface!");
        }
        return surface;
    }

    private static Rect adjustSourceRectForInsets(Rect insets, Rect srcRect) {
        if (srcRect == null) {
            return insets;
        }
        if (insets != null) {
            srcRect.offset(insets.left, insets.top);
        }
        return srcRect;
    }

    /**
     * Contains the result of a PixelCopy request
     */
    public static final class Result {
        private int mStatus;
        private Bitmap mBitmap;

        private Result(@CopyResultStatus int status, Bitmap bitmap) {
            mStatus = status;
            mBitmap = bitmap;
        }

        /**
         * Returns the status of the copy request.
         */
        public @CopyResultStatus int getStatus() {
            return mStatus;
        }

        private void validateStatus() {
            if (mStatus != SUCCESS) {
                throw new IllegalStateException("Copy request didn't succeed, status = " + mStatus);
            }
        }

        /**
         * If the PixelCopy {@link Request} was given a destination bitmap with
         * {@link Request.Builder#setDestinationBitmap(Bitmap)} then the returned bitmap will be
         * the same as the one given. If no destination bitmap was provided, then this
         * will contain the automatically allocated Bitmap to hold the result.
         *
         * @return the Bitmap the copy request was stored in.
         * @throws IllegalStateException if {@link #getStatus()} is not SUCCESS
         */
        public @NonNull Bitmap getBitmap() {
            validateStatus();
            return mBitmap;
        }
    }

    /**
     * Represents a PixelCopy request.
     *
     * To create a copy request, use either of the PixelCopy.Request.ofWindow or
     * PixelCopy.Request.ofSurface factories to create a {@link Request.Builder} for the
     * given source content. After setting any optional parameters, such as
     * {@link Builder#setSourceRect(Rect)}, build the request with {@link Builder#build()} and
     * then execute it with {@link PixelCopy#request(Request, Executor, Consumer)}
     */
    public static final class Request {
        private final Surface mSource;
        private final Rect mSourceInsets;
        private Rect mSrcRect;
        private Bitmap mDest;

        private Request(Surface source, Rect sourceInsets) {
            this.mSource = source;
            this.mSourceInsets = sourceInsets;
        }

        /**
         * A builder to create the complete PixelCopy request, which is then executed by calling
         * {@link #request(Request, Executor, Consumer)} with the built request returned from
         * {@link #build()}
         */
        public static final class Builder {
            private Request mRequest;

            private Builder(Request request) {
                mRequest = request;
            }

            /**
             * Creates a PixelCopy Builder for the given {@link Window}
             * @param source The Window to copy from
             * @return A {@link Builder} builder to set the optional params & build the request
             */
            @SuppressLint("BuilderSetStyle")
            public static @NonNull Builder ofWindow(@NonNull Window source) {
                final Rect insets = new Rect();
                final Surface surface = sourceForWindow(source, insets);
                return new Builder(new Request(surface, insets));
            }

            /**
             * Creates a PixelCopy Builder for the {@link Window} that the given {@link View} is
             * attached to.
             *
             * Note that this copy request is not cropped to the area the View occupies by default.
             * If that behavior is desired, use {@link View#getLocationInWindow(int[])} combined
             * with {@link Builder#setSourceRect(Rect)} to set a crop area to restrict the copy
             * operation.
             *
             * @param source A View that {@link View#isAttachedToWindow() is attached} to a window
             *               that will be used to retrieve the window to copy from.
             * @return A {@link Builder} builder to set the optional params & build the request
             */
            @SuppressLint("BuilderSetStyle")
            public static @NonNull Builder ofWindow(@NonNull View source) {
                if (source == null || !source.isAttachedToWindow()) {
                    throw new IllegalArgumentException(
                            "View must not be null & must be attached to window");
                }
                final Rect insets = new Rect();
                Surface surface = null;
                final ViewRootImpl root = source.getViewRootImpl();
                if (root != null) {
                    surface = root.mSurface;
                    insets.set(root.mWindowAttributes.surfaceInsets);
                }
                if (surface == null || !surface.isValid()) {
                    throw new IllegalArgumentException(
                            "Window doesn't have a backing surface!");
                }
                return new Builder(new Request(surface, insets));
            }

            /**
             * Creates a PixelCopy Builder for the given {@link Surface}
             *
             * @param source The Surface to copy from. Must be {@link Surface#isValid() valid}.
             * @return A {@link Builder} builder to set the optional params & build the request
             */
            @SuppressLint("BuilderSetStyle")
            public static @NonNull Builder ofSurface(@NonNull Surface source) {
                if (source == null || !source.isValid()) {
                    throw new IllegalArgumentException("Source must not be null & must be valid");
                }
                return new Builder(new Request(source, null));
            }

            /**
             * Creates a PixelCopy Builder for the {@link Surface} belonging to the
             * given {@link SurfaceView}
             *
             * @param source The SurfaceView to copy from. The backing surface must be
             *               {@link Surface#isValid() valid}
             * @return A {@link Builder} builder to set the optional params & build the request
             */
            @SuppressLint("BuilderSetStyle")
            public static @NonNull Builder ofSurface(@NonNull SurfaceView source) {
                return ofSurface(source.getHolder().getSurface());
            }

            private void requireNotBuilt() {
                if (mRequest == null) {
                    throw new IllegalStateException("build() already called on this builder");
                }
            }

            /**
             * Sets the region of the source to copy from. By default, the entire source is copied
             * to the output. If only a subset of the source is necessary to be copied, specifying
             * a srcRect will improve performance by reducing
             * the amount of data being copied.
             *
             * @param srcRect The area of the source to read from. Null or empty will be treated to
             *                mean the entire source
             * @return this
             */
            public @NonNull Builder setSourceRect(@Nullable Rect srcRect) {
                requireNotBuilt();
                mRequest.mSrcRect = srcRect;
                return this;
            }

            /**
             * Specifies the output bitmap in which to store the result. By default, a Bitmap of
             * format {@link android.graphics.Bitmap.Config#ARGB_8888} with a width & height
             * matching that of the {@link #setSourceRect(Rect) source area} will be created to
             * place the result.
             *
             * @param destination The bitmap to store the result, or null to have a bitmap
             *                    automatically created of the appropriate size. If not null, must
             *                    not be {@link Bitmap#isRecycled() recycled} and must be
             *                    {@link Bitmap#isMutable() mutable}.
             * @return this
             */
            public @NonNull Builder setDestinationBitmap(@Nullable Bitmap destination) {
                requireNotBuilt();
                if (destination != null) {
                    validateBitmapDest(destination);
                }
                mRequest.mDest = destination;
                return this;
            }

            /**
             * @return The built {@link PixelCopy.Request}
             */
            public @NonNull Request build() {
                requireNotBuilt();
                Request ret = mRequest;
                mRequest = null;
                return ret;
            }
        }

        /**
         * @return The destination bitmap as set by {@link Builder#setDestinationBitmap(Bitmap)}
         */
        public @Nullable Bitmap getDestinationBitmap() {
            return mDest;
        }

        /**
         * @return The source rect to copy from as set by {@link Builder#setSourceRect(Rect)}
         */
        public @Nullable Rect getSourceRect() {
            return mSrcRect;
        }

        /**
         * @hide
         */
        public void request(@NonNull Executor callbackExecutor,
                            @NonNull Consumer<Result> listener) {
            if (!mSource.isValid()) {
                callbackExecutor.execute(() -> listener.accept(
                        new Result(ERROR_SOURCE_INVALID, null)));
                return;
            }
            HardwareRenderer.copySurfaceInto(mSource, new HardwareRenderer.CopyRequest(
                    adjustSourceRectForInsets(mSourceInsets, mSrcRect), mDest) {
                @Override
                public void onCopyFinished(int result) {
                    callbackExecutor.execute(() -> listener.accept(
                            new Result(result, mDestinationBitmap)));
                }
            });
        }
    }

    /**
     * Executes the pixel copy request
     * @param request The request to execute
     * @param callbackExecutor The executor to run the callback on
     * @param listener The callback for when the copy request is completed
     */
    public static void request(@NonNull Request request, @NonNull Executor callbackExecutor,
                               @NonNull Consumer<Result> listener) {
        request.request(callbackExecutor, listener);
    }

    private PixelCopy() {}
}
