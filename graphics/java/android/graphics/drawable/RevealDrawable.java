/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff.Mode;
import android.graphics.Rect;
import android.graphics.Shader.TileMode;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.SparseArray;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.util.ArrayList;

/**
 * An extension of LayerDrawable that is intended to react to touch hotspots
 * and reveal the second layer atop the first.
 * <p>
 * It can be defined in an XML file with the <code>&lt;reveal&gt;</code> element.
 * Each Drawable in the transition is defined in a nested <code>&lt;item&gt;</code>.
 * For more information, see the guide to <a href="{@docRoot}
 * guide/topics/resources/drawable-resource.html">Drawable Resources</a>.
 *
 * @attr ref android.R.styleable#LayerDrawableItem_left
 * @attr ref android.R.styleable#LayerDrawableItem_top
 * @attr ref android.R.styleable#LayerDrawableItem_right
 * @attr ref android.R.styleable#LayerDrawableItem_bottom
 * @attr ref android.R.styleable#LayerDrawableItem_drawable
 * @attr ref android.R.styleable#LayerDrawableItem_id
 */
public class RevealDrawable extends LayerDrawable {
    private final Rect mTempRect = new Rect();

    /** Lazily-created map of touch hotspot IDs to ripples. */
    private SparseArray<Ripple> mTouchedRipples;

    /** Lazily-created list of actively animating ripples. */
    private ArrayList<Ripple> mActiveRipples;

    /** Lazily-created runnable for scheduling invalidation. */
    private Runnable mAnimationRunnable;

    /** Whether the animation runnable has been posted. */
    private boolean mAnimating;

    /** Target density, used to scale density-independent pixels. */
    private float mDensity = 1.0f;

    // Masking layer.
    private Bitmap mMaskBitmap;
    private Canvas mMaskCanvas;
    private Paint mMaskPaint;

    // Reveal layer.
    private Bitmap mRevealBitmap;
    private Canvas mRevealCanvas;
    private Paint mRevealPaint;

    /**
     * Create a new reveal drawable with the specified list of layers. At least
     * two layers are required for this drawable to work properly.
     */
    public RevealDrawable(Drawable[] layers) {
        this(new RevealState(null, null, null), layers);
    }

    /**
     * Create a new reveal drawable with no layers. To work correctly, at least
     * two layers must be added to this drawable.
     *
     * @see #RevealDrawable(Drawable[])
     */
    RevealDrawable() {
        this(new RevealState(null, null, null), (Resources) null);
    }

    private RevealDrawable(RevealState state, Resources res) {
        super(state, res);
    }

    private RevealDrawable(RevealState state, Drawable[] layers) {
        super(layers, state);
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs);

        setTargetDensity(r.getDisplayMetrics());
        setPaddingMode(PADDING_MODE_STACK);
    }

    @Override
    LayerState createConstantState(LayerState state, Resources res) {
        return new RevealState((RevealState) state, this, res);
    }

    /**
     * Set the density at which this drawable will be rendered.
     *
     * @param metrics The display metrics for this drawable.
     */
    private void setTargetDensity(DisplayMetrics metrics) {
        if (mDensity != metrics.density) {
            mDensity = metrics.density;
            invalidateSelf();
        }
    }

    /**
     * @hide until hotspot APIs are finalized
     */
    @Override
    public boolean supportsHotspots() {
        return true;
    }

    /**
     * @hide until hotspot APIs are finalized
     */
    @Override
    public void setHotspot(int id, float x, float y) {
        if (mTouchedRipples == null) {
            mTouchedRipples = new SparseArray<Ripple>();
            mActiveRipples = new ArrayList<Ripple>();
        }

        final Ripple ripple = mTouchedRipples.get(id);
        if (ripple == null) {
            final Rect padding = mTempRect;
            getPadding(padding);

            final Ripple newRipple = new Ripple(getBounds(), padding, x, y, mDensity);
            newRipple.enter();

            mActiveRipples.add(newRipple);
            mTouchedRipples.put(id, newRipple);
        } else {
            ripple.move(x, y);
        }

        scheduleAnimation();
    }

    /**
     * @hide until hotspot APIs are finalized
     */
    @Override
    public void removeHotspot(int id) {
        if (mTouchedRipples == null) {
            return;
        }

        final Ripple ripple = mTouchedRipples.get(id);
        if (ripple != null) {
            ripple.exit();

            mTouchedRipples.remove(id);
            scheduleAnimation();
        }
    }

    /**
     * @hide until hotspot APIs are finalized
     */
    @Override
    public void clearHotspots() {
        if (mTouchedRipples == null) {
            return;
        }

        final int n = mTouchedRipples.size();
        for (int i = 0; i < n; i++) {
            final Ripple ripple = mTouchedRipples.valueAt(i);
            ripple.exit();
        }

        if (n > 0) {
            mTouchedRipples.clear();
            scheduleAnimation();
        }
    }

    /**
     * Schedules the next animation, if necessary.
     */
    private void scheduleAnimation() {
        if (mActiveRipples == null || mActiveRipples.isEmpty()) {
            mAnimating = false;
        } else if (!mAnimating) {
            mAnimating = true;

            if (mAnimationRunnable == null) {
                mAnimationRunnable = new Runnable() {
                    @Override
                    public void run() {
                        mAnimating = false;
                        scheduleAnimation();
                        invalidateSelf();
                    }
                };
            }

            scheduleSelf(mAnimationRunnable, SystemClock.uptimeMillis() + 1000 / 60);
        }
    }

    @Override
    public void draw(Canvas canvas) {
        final Drawable lower = getDrawable(0);
        lower.draw(canvas);

        // No ripples? No problem.
        if (mActiveRipples == null || mActiveRipples.isEmpty()) {
            return;
        }

        // Ensure we have a mask buffer.
        final Rect bounds = getBounds();
        final int width = bounds.width();
        final int height = bounds.height();
        if (mMaskBitmap == null) {
            mMaskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8);
            mMaskCanvas = new Canvas(mMaskBitmap);
            mMaskPaint = new Paint();
            mMaskPaint.setAntiAlias(true);
        } else if (mMaskBitmap.getHeight() < height || mMaskBitmap.getWidth() < width) {
            mMaskBitmap.recycle();
            mMaskBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8);
        }

        // Ensure we have a reveal buffer.
        if (mRevealBitmap == null) {
            mRevealBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            mRevealCanvas = new Canvas(mRevealBitmap);
            mRevealPaint = new Paint();
            mRevealPaint.setAntiAlias(true);
            mRevealPaint.setShader(new BitmapShader(mRevealBitmap, TileMode.CLAMP, TileMode.CLAMP));
        } else if (mRevealBitmap.getHeight() < height || mRevealBitmap.getWidth() < width) {
            mRevealBitmap.recycle();
            mRevealBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        }

        // Draw ripples into the mask buffer.
        mMaskCanvas.drawColor(Color.TRANSPARENT, Mode.CLEAR);
        int n = mActiveRipples.size();
        for (int i = 0; i < n; i++) {
            final Ripple ripple = mActiveRipples.get(i);
            if (!ripple.active()) {
                mActiveRipples.remove(i);
                i--;
                n--;
            } else {
                ripple.draw(mMaskCanvas, mMaskPaint);
            }
        }

        // Draw upper layer into the reveal buffer.
        mRevealCanvas.drawColor(Color.TRANSPARENT, Mode.CLEAR);
        final Drawable upper = getDrawable(1);
        upper.draw(mRevealCanvas);

        // Draw mask buffer onto the canvas using the reveal shader.
        canvas.drawBitmap(mMaskBitmap, 0, 0, mRevealPaint);
    }

    private static class RevealState extends LayerState {
        public RevealState(RevealState orig, RevealDrawable owner, Resources res) {
            super(orig, owner, res);
        }

        @Override
        public Drawable newDrawable() {
            return newDrawable(null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new RevealDrawable(this, res);
        }
    }
}
