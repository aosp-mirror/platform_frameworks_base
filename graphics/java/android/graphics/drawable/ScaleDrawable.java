/*
 * Copyright (C) 2006 The Android Open Source Project
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

import com.android.internal.R;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;

import java.io.IOException;

/**
 * A Drawable that changes the size of another Drawable based on its current
 * level value. You can control how much the child Drawable changes in width
 * and height based on the level, as well as a gravity to control where it is
 * placed in its overall container. Most often used to implement things like
 * progress bars.
 * <p>
 * The default level may be specified from XML using the
 * {@link android.R.styleable#ScaleDrawable_level android:level} property. When
 * this property is not specified, the default level is 0, which corresponds to
 * zero height and/or width depending on the values specified for
 * {@code android.R.styleable#ScaleDrawable_scaleWidth scaleWidth} and
 * {@code android.R.styleable#ScaleDrawable_scaleHeight scaleHeight}. At run
 * time, the level may be set via {@link #setLevel(int)}.
 * <p>
 * A scale drawable may be defined in an XML file with the {@code <scale>}
 * element. For more information, see the guide to
 * <a href="{@docRoot}guide/topics/resources/drawable-resource.html">Drawable
 * Resources</a>.
 *
 * @attr ref android.R.styleable#ScaleDrawable_scaleWidth
 * @attr ref android.R.styleable#ScaleDrawable_scaleHeight
 * @attr ref android.R.styleable#ScaleDrawable_scaleGravity
 * @attr ref android.R.styleable#ScaleDrawable_drawable
 * @attr ref android.R.styleable#ScaleDrawable_level
 */
public class ScaleDrawable extends DrawableWrapper {
    private static final int MAX_LEVEL = 10000;

    private final Rect mTmpRect = new Rect();

    private ScaleState mState;

    ScaleDrawable() {
        this(new ScaleState(null, null), null);
    }

    /**
     * Creates a new scale drawable with the specified gravity and scale
     * properties.
     *
     * @param drawable the drawable to scale
     * @param gravity gravity constant (see {@link Gravity} used to position
     *                the scaled drawable within the parent container
     * @param scaleWidth width scaling factor [0...1] to use then the level is
     *                   at the maximum value, or -1 to not scale width
     * @param scaleHeight height scaling factor [0...1] to use then the level
     *                    is at the maximum value, or -1 to not scale height
     */
    public ScaleDrawable(Drawable drawable, int gravity, float scaleWidth, float scaleHeight) {
        this(new ScaleState(null, null), null);

        mState.mGravity = gravity;
        mState.mScaleWidth = scaleWidth;
        mState.mScaleHeight = scaleHeight;

        setDrawable(drawable);
    }

    @Override
    public void inflate(@NonNull Resources r, @NonNull XmlPullParser parser,
            @NonNull AttributeSet attrs, @Nullable Theme theme)
            throws XmlPullParserException, IOException {
        final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.ScaleDrawable);

        // Inflation will advance the XmlPullParser and AttributeSet.
        super.inflate(r, parser, attrs, theme);

        updateStateFromTypedArray(a);
        verifyRequiredAttributes(a);
        a.recycle();

        updateLocalState();
    }

    @Override
    public void applyTheme(@NonNull Theme t) {
        super.applyTheme(t);

        final ScaleState state = mState;
        if (state == null) {
            return;
        }

        if (state.mThemeAttrs != null) {
            final TypedArray a = t.resolveAttributes(state.mThemeAttrs, R.styleable.ScaleDrawable);
            try {
                updateStateFromTypedArray(a);
                verifyRequiredAttributes(a);
            } catch (XmlPullParserException e) {
                rethrowAsRuntimeException(e);
            } finally {
                a.recycle();
            }
        }

        updateLocalState();
    }

    private void verifyRequiredAttributes(@NonNull TypedArray a) throws XmlPullParserException {
        // If we're not waiting on a theme, verify required attributes.
        if (getDrawable() == null && (mState.mThemeAttrs == null
                || mState.mThemeAttrs[R.styleable.ScaleDrawable_drawable] == 0)) {
            throw new XmlPullParserException(a.getPositionDescription()
                    + ": <scale> tag requires a 'drawable' attribute or "
                    + "child tag defining a drawable");
        }
    }

    private void updateStateFromTypedArray(@NonNull TypedArray a) {
        final ScaleState state = mState;
        if (state == null) {
            return;
        }

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mThemeAttrs = a.extractThemeAttrs();

        state.mScaleWidth = getPercent(a,
                R.styleable.ScaleDrawable_scaleWidth, state.mScaleWidth);
        state.mScaleHeight = getPercent(a,
                R.styleable.ScaleDrawable_scaleHeight, state.mScaleHeight);
        state.mGravity = a.getInt(
                R.styleable.ScaleDrawable_scaleGravity, state.mGravity);
        state.mUseIntrinsicSizeAsMin = a.getBoolean(
                R.styleable.ScaleDrawable_useIntrinsicSizeAsMinimum, state.mUseIntrinsicSizeAsMin);
        state.mInitialLevel = a.getInt(
                R.styleable.ScaleDrawable_level, state.mInitialLevel);
    }

    private static float getPercent(TypedArray a, int index, float defaultValue) {
        final int type = a.getType(index);
        if (type == TypedValue.TYPE_FRACTION || type == TypedValue.TYPE_NULL) {
            return a.getFraction(index, 1, 1, defaultValue);
        }

        // Coerce to float.
        final String s = a.getString(index);
        if (s != null) {
            if (s.endsWith("%")) {
                final String f = s.substring(0, s.length() - 1);
                return Float.parseFloat(f) / 100.0f;
            }
        }

        return defaultValue;
    }

    @Override
    public void draw(Canvas canvas) {
        final Drawable d = getDrawable();
        if (d != null && d.getLevel() != 0) {
            d.draw(canvas);
        }
    }

    @Override
    public int getOpacity() {
        final Drawable d = getDrawable();
        if (d.getLevel() == 0) {
            return PixelFormat.TRANSPARENT;
        }

        final int opacity = d.getOpacity();
        if (opacity == PixelFormat.OPAQUE && d.getLevel() < MAX_LEVEL) {
            return PixelFormat.TRANSLUCENT;
        }

        return opacity;
    }

    @Override
    protected boolean onLevelChange(int level) {
        super.onLevelChange(level);
        onBoundsChange(getBounds());
        invalidateSelf();
        return true;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        final Drawable d = getDrawable();
        final Rect r = mTmpRect;
        final boolean min = mState.mUseIntrinsicSizeAsMin;
        final int level = getLevel();

        int w = bounds.width();
        if (mState.mScaleWidth > 0) {
            final int iw = min ? d.getIntrinsicWidth() : 0;
            w -= (int) ((w - iw) * (MAX_LEVEL - level) * mState.mScaleWidth / MAX_LEVEL);
        }

        int h = bounds.height();
        if (mState.mScaleHeight > 0) {
            final int ih = min ? d.getIntrinsicHeight() : 0;
            h -= (int) ((h - ih) * (MAX_LEVEL - level) * mState.mScaleHeight / MAX_LEVEL);
        }

        final int layoutDirection = getLayoutDirection();
        Gravity.apply(mState.mGravity, w, h, bounds, r, layoutDirection);

        if (w > 0 && h > 0) {
            d.setBounds(r.left, r.top, r.right, r.bottom);
        }
    }

    @Override
    DrawableWrapperState mutateConstantState() {
        mState = new ScaleState(mState, null);
        return mState;
    }

    static final class ScaleState extends DrawableWrapper.DrawableWrapperState {
        /** Constant used to disable scaling for a particular dimension. */
        private static final float DO_NOT_SCALE = -1.0f;

        private int[] mThemeAttrs;

        float mScaleWidth = DO_NOT_SCALE;
        float mScaleHeight = DO_NOT_SCALE;
        int mGravity = Gravity.LEFT;
        boolean mUseIntrinsicSizeAsMin = false;
        int mInitialLevel = 0;

        ScaleState(ScaleState orig, Resources res) {
            super(orig, res);

            if (orig != null) {
                mScaleWidth = orig.mScaleWidth;
                mScaleHeight = orig.mScaleHeight;
                mGravity = orig.mGravity;
                mUseIntrinsicSizeAsMin = orig.mUseIntrinsicSizeAsMin;
                mInitialLevel = orig.mInitialLevel;
            }
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new ScaleDrawable(this, res);
        }
    }

    /**
     * Creates a new ScaleDrawable based on the specified constant state.
     * <p>
     * The resulting drawable is guaranteed to have a new constant state.
     *
     * @param state constant state from which the drawable inherits
     */
    private ScaleDrawable(ScaleState state, Resources res) {
        super(state, res);

        mState = state;

        updateLocalState();
    }

    private void updateLocalState() {
        setLevel(mState.mInitialLevel);
    }
}

