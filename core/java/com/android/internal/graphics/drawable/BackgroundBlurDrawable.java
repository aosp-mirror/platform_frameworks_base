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

package com.android.internal.graphics.drawable;

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiThread;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RenderNode;
import android.graphics.drawable.Drawable;
import android.util.ArraySet;
import android.util.Log;
import android.util.LongSparseArray;
import android.view.ViewRootImpl;
import android.view.ViewTreeObserver;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

/**
 * A drawable that keeps track of a blur region, pokes a hole under it, and propagates its state
 * to SurfaceFlinger.
 */
public final class BackgroundBlurDrawable extends Drawable {

    private static final String TAG = BackgroundBlurDrawable.class.getSimpleName();
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Aggregator mAggregator;
    private final RenderNode mRenderNode;
    private final Paint mPaint = new Paint();
    private final Path mRectPath = new Path();
    private final float[] mTmpRadii = new float[8];

    private boolean mVisible = true;

    // Confined to UiThread. The values are copied into a BlurRegion, which lives on
    // RenderThread to avoid interference with UiThread updates.
    private int mBlurRadius;
    private float mCornerRadiusTL;
    private float mCornerRadiusTR;
    private float mCornerRadiusBL;
    private float mCornerRadiusBR;
    private float mAlpha = 1;

    // Do not update from UiThread. This holds the latest position for this drawable. It is used
    // by the Aggregator from RenderThread to get the final position of the blur region sent to SF
    private final Rect mRect = new Rect();
    // This is called from a thread pool. The callbacks might come out of order w.r.t. the frame
    // number, so we send a Runnable holding the actual update to the Aggregator. The Aggregator
    // can apply the update on RenderThread when processing that same frame.
    @VisibleForTesting
    public final RenderNode.PositionUpdateListener mPositionUpdateListener =
            new RenderNode.PositionUpdateListener() {
            @Override
            public void positionChanged(long frameNumber, int left, int top, int right,
                    int bottom) {
                mAggregator.onRenderNodePositionChanged(frameNumber, () -> {
                    mRect.set(left, top, right, bottom);
                });
            }

            @Override
            public void positionLost(long frameNumber) {
                mAggregator.onRenderNodePositionChanged(frameNumber, () -> {
                    mRect.setEmpty();
                });
            }
        };

    private BackgroundBlurDrawable(Aggregator aggregator) {
        mAggregator = aggregator;
        mPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        mPaint.setColor(Color.TRANSPARENT);
        mPaint.setAntiAlias(true);
        mRenderNode = new RenderNode("BackgroundBlurDrawable");
        mRenderNode.addPositionUpdateListener(mPositionUpdateListener);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (mRectPath.isEmpty() || !isVisible() || getAlpha() == 0) {
            return;
        }

        canvas.drawPath(mRectPath, mPaint);
        canvas.drawRenderNode(mRenderNode);
    }

    /**
     * Color that will be alpha blended on top of the blur.
     */
    public void setColor(@ColorInt int color) {
        mPaint.setColor(color);
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (changed) {
            mVisible = visible;
            mAggregator.onBlurDrawableUpdated(this);
        }
        return changed;
    }

    @Override
    public void setAlpha(int alpha) {
        if (mAlpha != alpha / 255f) {
            mAlpha = alpha / 255f;
            invalidateSelf();
            mAggregator.onBlurDrawableUpdated(this);
        }
    }

    /**
     * Blur radius in pixels.
     */
    public void setBlurRadius(int blurRadius) {
        if (mBlurRadius != blurRadius) {
            mBlurRadius = blurRadius;
            invalidateSelf();
            mAggregator.onBlurDrawableUpdated(this);
        }
    }

    /**
     * Sets the corner radius, in degrees.
     */
    public void setCornerRadius(float cornerRadius) {
        setCornerRadius(cornerRadius, cornerRadius, cornerRadius, cornerRadius);
    }

    /**
     * Sets the corner radius in degrees.
     * @param cornerRadiusTL top left radius.
     * @param cornerRadiusTR top right radius.
     * @param cornerRadiusBL bottom left radius.
     * @param cornerRadiusBR bottom right radius.
     */
    public void setCornerRadius(float cornerRadiusTL, float cornerRadiusTR, float cornerRadiusBL,
            float cornerRadiusBR) {
        if (mCornerRadiusTL != cornerRadiusTL
                || mCornerRadiusTR != cornerRadiusTR
                || mCornerRadiusBL != cornerRadiusBL
                || mCornerRadiusBR != cornerRadiusBR) {
            mCornerRadiusTL = cornerRadiusTL;
            mCornerRadiusTR = cornerRadiusTR;
            mCornerRadiusBL = cornerRadiusBL;
            mCornerRadiusBR = cornerRadiusBR;
            updatePath();
            invalidateSelf();
            mAggregator.onBlurDrawableUpdated(this);
        }
    }

    @Override
    public void setBounds(int left, int top, int right, int bottom) {
        super.setBounds(left, top, right, bottom);
        mRenderNode.setPosition(left, top, right, bottom);
        updatePath();
    }

    private void updatePath() {
        mTmpRadii[0] = mTmpRadii[1] = mCornerRadiusTL;
        mTmpRadii[2] = mTmpRadii[3] = mCornerRadiusTR;
        mTmpRadii[4] = mTmpRadii[5] = mCornerRadiusBL;
        mTmpRadii[6] = mTmpRadii[7] = mCornerRadiusBR;
        mRectPath.reset();
        if (getAlpha() == 0 || !isVisible()) {
            return;
        }
        Rect bounds = getBounds();
        mRectPath.addRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom, mTmpRadii,
                Path.Direction.CW);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        throw new IllegalArgumentException("not implemented");
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public String toString() {
        return "BackgroundBlurDrawable{"
            + "blurRadius=" + mBlurRadius
            + ", corners={" + mCornerRadiusTL
            + "," + mCornerRadiusTR
            + "," + mCornerRadiusBL
            + "," + mCornerRadiusBR
            + "}, alpha=" + mAlpha
            + ", visible=" + mVisible
            + "}";
    }

    /**
     * Responsible for keeping track of all blur regions of a {@link ViewRootImpl} and posting a
     * message when it's time to propagate them.
     */
    public static final class Aggregator {
        private final Object mRtLock = new Object();
        // Maintains a list of all *visible* blur drawables. Confined to  UI thread
        private final ArraySet<BackgroundBlurDrawable> mDrawables = new ArraySet();
        @GuardedBy("mRtLock")
        private final LongSparseArray<ArraySet<Runnable>> mFrameRtUpdates = new LongSparseArray();
        private long mLastFrameNumber = 0;
        private BlurRegion[] mLastFrameBlurRegions = null;
        private final ViewRootImpl mViewRoot;
        private BlurRegion[] mTmpBlurRegionsForFrame = new BlurRegion[0];
        private boolean mHasUiUpdates;
        private ViewTreeObserver.OnPreDrawListener mOnPreDrawListener;

        public Aggregator(ViewRootImpl viewRoot) {
            mViewRoot = viewRoot;
        }

        /**
         * Creates a blur region with default radius.
         */
        public BackgroundBlurDrawable createBackgroundBlurDrawable(Context context) {
            BackgroundBlurDrawable drawable = new BackgroundBlurDrawable(this);
            drawable.setBlurRadius(context.getResources().getDimensionPixelSize(
                    R.dimen.default_background_blur_radius));
            return drawable;
        }

        /**
         * Called when a BackgroundBlurDrawable has been updated
         */
        @UiThread
        void onBlurDrawableUpdated(BackgroundBlurDrawable drawable) {
            final boolean shouldBeDrawn =
                    drawable.mAlpha != 0 && drawable.mBlurRadius > 0 && drawable.mVisible;
            final boolean isDrawn = mDrawables.contains(drawable);
            if (shouldBeDrawn) {
                mHasUiUpdates = true;
                if (!isDrawn) {
                    mDrawables.add(drawable);
                    if (DEBUG) {
                        Log.d(TAG, "Add " + drawable);
                    }
                } else {
                    if (DEBUG) {
                        Log.d(TAG, "Update " + drawable);
                    }
                }
            } else if (!shouldBeDrawn && isDrawn) {
                mHasUiUpdates = true;
                mDrawables.remove(drawable);
                if (DEBUG) {
                    Log.d(TAG, "Remove " + drawable);
                }
            }

            if (mOnPreDrawListener == null && mViewRoot.getView() != null
                    && hasRegions()) {
                registerPreDrawListener();
            }
        }

        private void registerPreDrawListener() {
            mOnPreDrawListener = () -> {
                final boolean hasUiUpdates = hasUpdates();

                if (hasUiUpdates || hasRegions()) {
                    final BlurRegion[] blurRegionsForNextFrame = getBlurRegionsCopyForRT();

                    mViewRoot.registerRtFrameCallback(frame -> {
                        synchronized (mRtLock) {
                            mLastFrameNumber = frame;
                            mLastFrameBlurRegions = blurRegionsForNextFrame;
                            handleDispatchBlurTransactionLocked(
                                    frame, blurRegionsForNextFrame, hasUiUpdates);
                        }
                    });
                }
                if (!hasRegions() && mViewRoot.getView() != null) {
                    mViewRoot.getView().getViewTreeObserver()
                            .removeOnPreDrawListener(mOnPreDrawListener);
                    mOnPreDrawListener = null;
                }
                return true;
            };

            mViewRoot.getView().getViewTreeObserver().addOnPreDrawListener(mOnPreDrawListener);
        }

        // Called from a thread pool
        void onRenderNodePositionChanged(long frameNumber, Runnable update) {
            // One of the blur region's position has changed, so we have to send an updated list
            // of blur regions to SurfaceFlinger for this frame.
            synchronized (mRtLock) {
                ArraySet<Runnable> frameRtUpdates = mFrameRtUpdates.get(frameNumber);
                if (frameRtUpdates == null) {
                    frameRtUpdates = new ArraySet<>();
                    mFrameRtUpdates.put(frameNumber, frameRtUpdates);
                }
                frameRtUpdates.add(update);

                if (mLastFrameNumber == frameNumber) {
                    // The transaction for this frame has already been sent, so we have to manually
                    // trigger sending a transaction here in order to apply this position update
                    handleDispatchBlurTransactionLocked(frameNumber, mLastFrameBlurRegions, true);
                }
            }

        }

        /**
         * @return true if there are any updates that need to be sent to SF
         */
        @UiThread
        public boolean hasUpdates() {
            return mHasUiUpdates;
        }

        /**
         * @return true if there are any visible blur regions
         */
        @UiThread
        public boolean hasRegions() {
            return mDrawables.size() > 0;
        }

        /**
         * @return an array of BlurRegions, which are holding a copy of the information in
         *         all the currently visible BackgroundBlurDrawables
         */
        @UiThread
        public BlurRegion[] getBlurRegionsCopyForRT() {
            if (mHasUiUpdates) {
                mTmpBlurRegionsForFrame = new BlurRegion[mDrawables.size()];
                for (int i = 0; i < mDrawables.size(); i++) {
                    mTmpBlurRegionsForFrame[i] = new BlurRegion(mDrawables.valueAt(i));
                }
                mHasUiUpdates = false;
            }

            return mTmpBlurRegionsForFrame;
        }

        /**
         * Called on RenderThread.
         *
         * @return true if it is necessary to send an update to Sf this frame
         */
        @GuardedBy("mRtLock")
        @VisibleForTesting
        public float[][] getBlurRegionsForFrameLocked(long frameNumber,
                BlurRegion[] blurRegionsForFrame, boolean forceUpdate) {
            if (!forceUpdate && (mFrameRtUpdates.size() == 0
                        || mFrameRtUpdates.keyAt(0) > frameNumber)) {
                return null;
            }

            // mFrameRtUpdates holds position updates coming from a thread pool span from
            // RenderThread. At this point, all position updates for frame frameNumber should
            // have been added to mFrameRtUpdates.
            // Here, we apply all updates for frames <= frameNumber in case some previous update
            // has been missed. This also protects mFrameRtUpdates from memory leaks.
            while (mFrameRtUpdates.size() != 0 && mFrameRtUpdates.keyAt(0) <= frameNumber) {
                final ArraySet<Runnable> frameUpdates = mFrameRtUpdates.valueAt(0);
                mFrameRtUpdates.removeAt(0);
                for (int i = 0; i < frameUpdates.size(); i++) {
                    frameUpdates.valueAt(i).run();
                }
            }

            if (DEBUG) {
                Log.d(TAG, "Dispatching " + blurRegionsForFrame.length + " blur regions:");
            }

            final float[][] blurRegionsArray = new float[blurRegionsForFrame.length][];
            for (int i = 0; i < blurRegionsArray.length; i++) {
                blurRegionsArray[i] = blurRegionsForFrame[i].toFloatArray();
                if (DEBUG) {
                    Log.d(TAG, blurRegionsForFrame[i].toString());
                }
            }
            return blurRegionsArray;
        }

        /**
         * Dispatch all blur regions if there are any ui or position updates for that frame.
         */
        @GuardedBy("mRtLock")
        private void handleDispatchBlurTransactionLocked(long frameNumber, BlurRegion[] blurRegions,
                boolean forceUpdate) {
            float[][] blurRegionsArray =
                    getBlurRegionsForFrameLocked(frameNumber, blurRegions, forceUpdate);
            if (blurRegionsArray != null) {
                mViewRoot.dispatchBlurRegions(blurRegionsArray, frameNumber);
            }
        }

    }

    /**
     * Wrapper for sending blur data to SurfaceFlinger
     * Confined to RenderThread.
     */
    public static final class BlurRegion {
        public final int blurRadius;
        public final float cornerRadiusTL;
        public final float cornerRadiusTR;
        public final float cornerRadiusBL;
        public final float cornerRadiusBR;
        public final float alpha;
        public final Rect rect;

        BlurRegion(BackgroundBlurDrawable drawable) {
            alpha = drawable.mAlpha;
            blurRadius = drawable.mBlurRadius;
            cornerRadiusTL = drawable.mCornerRadiusTL;
            cornerRadiusTR = drawable.mCornerRadiusTR;
            cornerRadiusBL = drawable.mCornerRadiusBL;
            cornerRadiusBR = drawable.mCornerRadiusBR;
            rect = drawable.mRect;
        }

        /**
         * Serializes this class into a float array that's more JNI friendly.
         */
        float[] toFloatArray() {
            final float[] floatArray = new float[10];
            floatArray[0] = blurRadius;
            floatArray[1] = alpha;
            floatArray[2] = rect.left;
            floatArray[3] = rect.top;
            floatArray[4] = rect.right;
            floatArray[5] = rect.bottom;
            floatArray[6] = cornerRadiusTL;
            floatArray[7] = cornerRadiusTR;
            floatArray[8] = cornerRadiusBL;
            floatArray[9] = cornerRadiusBR;
            return floatArray;
        }

        @Override
        public String toString() {
            return "BlurRegion{"
                    + "blurRadius=" + blurRadius
                    + ", corners={" + cornerRadiusTL
                    + "," + cornerRadiusTR
                    + "," + cornerRadiusBL
                    + "," + cornerRadiusBR
                    + "}, alpha=" + alpha
                    + ", rect=" + rect
                    + "}";
        }
    }
}
