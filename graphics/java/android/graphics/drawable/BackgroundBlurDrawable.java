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

package android.graphics.drawable;

import android.annotation.ColorInt;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
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
import android.util.ArrayMap;
import android.util.Log;
import android.view.SurfaceControl;
import android.view.View;
import android.view.ViewRootImpl;

/**
 * A drawable that keeps track of a blur region, pokes a hole under it, and propagates its state
 * to SurfaceFlinger.
 *
 * @hide
 */
@SystemApi
public final class BackgroundBlurDrawable extends Drawable {
    private static final String TAG = BackgroundBlurDrawable.class.getSimpleName();
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final RenderNode mRenderNode;
    private final Paint mPaint = new Paint();
    private final Path mRectPath = new Path();
    private final float[] mTmpRadii = new float[8];
    private final SurfaceControl.BlurRegion mBlurRegion = new SurfaceControl.BlurRegion();

    private Aggregator mAggregator;

    // This will be called from a thread pool.
    private final RenderNode.PositionUpdateListener mPositionUpdateListener =
            new RenderNode.PositionUpdateListener() {
            @Override
            public void positionChanged(long frameNumber, int left, int top, int right,
                    int bottom) {
                if (mAggregator == null) {
                    mBlurRegion.rect.set(left, top, right, bottom);
                } else {
                    synchronized (mAggregator) {
                        mBlurRegion.rect.set(left, top, right, bottom);
                        mAggregator.onBlurRegionUpdated(BackgroundBlurDrawable.this, mBlurRegion);
                    }
                }
            }

            @Override
            public void positionLost(long frameNumber) {
                if (mAggregator == null) {
                    mBlurRegion.rect.setEmpty();
                } else {
                    synchronized (mAggregator) {
                        mBlurRegion.rect.setEmpty();
                        mAggregator.onBlurRegionUpdated(BackgroundBlurDrawable.this, mBlurRegion);
                    }
                }
            }
        };

    @RequiresPermission(android.Manifest.permission.USE_BACKGROUND_BLUR)
    public BackgroundBlurDrawable() {
        mPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
        mPaint.setColor(Color.TRANSPARENT);
        mRenderNode = new RenderNode("BackgroundBlurDrawable");
        mRenderNode.addPositionUpdateListener(mPositionUpdateListener);
    }

    /**
     * @hide
     */
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

    /**
     * @hide
     */
    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (changed) {
            mBlurRegion.visible = visible;
        }
        return changed;
    }

    /**
     * @hide
     */
    @Override
    public void setAlpha(int alpha) {
        mBlurRegion.alpha = alpha / 255f;
        invalidateSelf();
    }

    /**
     * Blur radius in pixels.
     */
    public void setBlurRadius(int blurRadius) {
        mBlurRegion.blurRadius = blurRadius;
        invalidateSelf();
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
        maybeRunSynchronized(() -> {
            mBlurRegion.cornerRadiusTL = cornerRadiusTL;
            mBlurRegion.cornerRadiusTR = cornerRadiusTR;
            mBlurRegion.cornerRadiusBL = cornerRadiusBL;
            mBlurRegion.cornerRadiusBR = cornerRadiusBR;
        });
        updatePath();
        invalidateSelf();
    }

    @Override
    public void setBounds(int left, int top, int right, int bottom) {
        super.setBounds(left, top, right, bottom);
        mRenderNode.setPosition(left, top, right, bottom);
        updatePath();
    }

    private void updatePath() {
        maybeRunSynchronized(() -> {
            mTmpRadii[0] = mTmpRadii[1] = mBlurRegion.cornerRadiusTL;
            mTmpRadii[2] = mTmpRadii[3] = mBlurRegion.cornerRadiusTR;
            mTmpRadii[4] = mTmpRadii[5] = mBlurRegion.cornerRadiusBL;
            mTmpRadii[6] = mTmpRadii[7] = mBlurRegion.cornerRadiusBR;
        });

        mRectPath.reset();
        if (getAlpha() == 0 || !isVisible()) {
            return;
        }
        Rect bounds = getBounds();
        mRectPath.addRoundRect(bounds.left, bounds.top, bounds.right, bounds.bottom, mTmpRadii,
                Path.Direction.CW);
    }

    /**
     * @hide
     */
    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        throw new IllegalArgumentException("not implemented");
    }

    /**
     * @hide
     */
    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    /**
     *  @hide
     */
    @Override
    public void onAttached(@NonNull View v) {
        super.onAttached(v);
        mAggregator = v.getViewRootImpl().getBlurRegionAggregator();
    }

    /**
     *  @hide
     */
    @Override
    public void onDetached(@NonNull View v) {
        super.onDetached(v);
        mAggregator = null;
    }

    /**
     * The Aggregator is called from the RenderThread to aggregate all blur regions and send them
     * to SurfaceFlinger. Since the BackgroundBlurDrawable could be updated at any time from the
     * main thread, we need to synchronize the two threads. The BackgroundBlurDrawable may be
     * instantiated before the ViewRootImpl is created, i.e. before the Aggregator is created.
     * In that case, updates are not synchronized.
     */
    private void maybeRunSynchronized(Runnable r) {
        if (mAggregator == null) {
            r.run();
        } else {
            synchronized (mAggregator) {
                r.run();
            }
        }
    }

    /**
     * Responsible for keeping track of all blur regions of a {@link ViewRootImpl} and posting a
     * message when it's time to propagate them.
     *
     * @hide
     */
    public static final class Aggregator {

        private final ArrayMap<BackgroundBlurDrawable, SurfaceControl.BlurRegion> mBlurRegions =
                new ArrayMap<>();
        private final ViewRootImpl mViewRoot;
        private float[][] mTmpBlurRegionsArray;
        private boolean mNeedsUpdate;

        public Aggregator(ViewRootImpl viewRoot) {
            mViewRoot = viewRoot;
        }

        /**
         * Called from RenderThread only, already locked.
         * @param drawable
         * @param blurRegion
         */
        void onBlurRegionUpdated(BackgroundBlurDrawable drawable,
                SurfaceControl.BlurRegion blurRegion) {
            if (blurRegion.rect.isEmpty() || blurRegion.alpha == 0 || blurRegion.blurRadius == 0
                    || !blurRegion.visible) {
                mBlurRegions.remove(drawable);
                mNeedsUpdate = true;
                if (DEBUG) {
                    Log.d(TAG, "Remove " + blurRegion);
                }
            } else {
                mBlurRegions.put(drawable, blurRegion);
                mNeedsUpdate = true;
                if (DEBUG) {
                    Log.d(TAG, "Update " + blurRegion);
                }
            }
        }

        /**
         * If there are any blur regions visible on the screen at the moment.
         */
        public boolean hasRegions() {
            return mBlurRegions.size() > 0;
        }

        /**
         * Dispatch blur updates, if there were any.
         * @param frameNumber Frame where the update should happen.
         */
        public void dispatchBlurTransactionIfNeeded(long frameNumber) {
            synchronized (this) {
                if (!mNeedsUpdate) {
                    return;
                }
                mNeedsUpdate = false;

                if (mTmpBlurRegionsArray == null
                        || mTmpBlurRegionsArray.length != mBlurRegions.size()) {
                    mTmpBlurRegionsArray = new float[mBlurRegions.size()][];
                }
                if (DEBUG) {
                    Log.d(TAG, "onBlurRegionUpdated will dispatch " + mTmpBlurRegionsArray.length
                            + " regions for frame " + frameNumber);
                }
                for (int i = 0; i < mTmpBlurRegionsArray.length; i++) {
                    mTmpBlurRegionsArray[i] = mBlurRegions.valueAt(i).toFloatArray();
                }

                mViewRoot.dispatchBlurRegions(mTmpBlurRegionsArray, frameNumber);
            }
        }
    }
}
