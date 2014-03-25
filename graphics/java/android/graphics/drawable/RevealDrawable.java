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
import android.content.res.Resources.Theme;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff.Mode;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
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

    /** Paint used to control appearance of ripples. */
    private Paint mRipplePaint;

    /** Paint used to control reveal layer masking. */
    private Paint mMaskingPaint;

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
        this(new RevealState(null, null, null), (Resources) null, null);
    }

    private RevealDrawable(RevealState state, Resources res) {
        super(state, res, null);
    }

    private RevealDrawable(RevealState state, Resources res, Theme theme) {
        super(state, res, theme);
    }

    private RevealDrawable(RevealState state, Drawable[] layers) {
        super(layers, state);
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs, theme);

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
        final int layerCount = getNumberOfLayers();
        if (layerCount == 0) {
            return;
        }

        getDrawable(0).draw(canvas);

        final Rect bounds = getBounds();
        final ArrayList<Ripple> activeRipples = mActiveRipples;
        if (layerCount == 1 || bounds.isEmpty() || activeRipples == null
                || activeRipples.isEmpty()) {
            // Nothing to reveal, we're done here.
            return;
        }

        if (mRipplePaint == null) {
            mRipplePaint = new Paint();
            mRipplePaint.setAntiAlias(true);
        }

        // Draw ripple mask into a buffer that merges using SRC_OVER.
        boolean needsMask = false;
        int layerSaveCount = -1;
        int n = activeRipples.size();
        for (int i = 0; i < n; i++) {
            final Ripple ripple = activeRipples.get(i);
            if (!ripple.active()) {
                activeRipples.remove(i);
                i--;
                n--;
            } else {
                if (layerSaveCount < 0) {
                    layerSaveCount = canvas.saveLayer(
                            bounds.left, bounds.top, bounds.right, bounds.bottom, null, 0);
                    // Ripples must be clipped to bounds, otherwise SRC_IN will
                    // miss them and we'll get artifacts.
                    canvas.clipRect(bounds);
                }

                needsMask |= ripple.draw(canvas, mRipplePaint);
            }
        }

        // If a layer was saved, it contains the ripple mask. Draw the reveal
        // into another layer and composite using SRC_IN, then composite onto
        // the original canvas.
        if (layerSaveCount >= 0) {
            if (needsMask) {
                if (mMaskingPaint == null) {
                    mMaskingPaint = new Paint();
                    mMaskingPaint.setXfermode(new PorterDuffXfermode(Mode.SRC_IN));
                }

                // TODO: When Drawable.setXfermode() is supported by all drawables,
                // we won't need an extra layer.
                canvas.saveLayer(
                        bounds.left, bounds.top, bounds.right, bounds.bottom, mMaskingPaint, 0);
                getDrawable(1).draw(canvas);
            }

            canvas.restoreToCount(layerSaveCount);
        }
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

        @Override
        public Drawable newDrawable(Resources res, Theme theme) {
            return new RevealDrawable(this, res, theme);
        }
    }
}
