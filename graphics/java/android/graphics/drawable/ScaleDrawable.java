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

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.Resources.Theme;
import android.graphics.*;
import android.graphics.PorterDuff.Mode;
import android.view.Gravity;
import android.util.AttributeSet;

import java.io.IOException;
import java.util.Collection;

/**
 * A Drawable that changes the size of another Drawable based on its current
 * level value.  You can control how much the child Drawable changes in width
 * and height based on the level, as well as a gravity to control where it is
 * placed in its overall container.  Most often used to implement things like
 * progress bars.
 *
 * <p>It can be defined in an XML file with the <code>&lt;scale></code> element. For more
 * information, see the guide to <a
 * href="{@docRoot}guide/topics/resources/drawable-resource.html">Drawable Resources</a>.</p>
 *
 * @attr ref android.R.styleable#ScaleDrawable_scaleWidth
 * @attr ref android.R.styleable#ScaleDrawable_scaleHeight
 * @attr ref android.R.styleable#ScaleDrawable_scaleGravity
 * @attr ref android.R.styleable#ScaleDrawable_drawable
 */
public class ScaleDrawable extends Drawable implements Drawable.Callback {
    private ScaleState mState;
    private boolean mMutated;
    private final Rect mTmpRect = new Rect();

    ScaleDrawable() {
        this(null, null);
    }

    public ScaleDrawable(Drawable drawable, int gravity, float scaleWidth, float scaleHeight) {
        this(null, null);

        mState.mDrawable = drawable;
        mState.mGravity = gravity;
        mState.mScaleWidth = scaleWidth;
        mState.mScaleHeight = scaleHeight;

        if (drawable != null) {
            drawable.setCallback(this);
        }
    }

    /**
     * Returns the drawable scaled by this ScaleDrawable.
     */
    public Drawable getDrawable() {
        return mState.mDrawable;
    }

    private static float getPercent(TypedArray a, int name, float defaultValue) {
        final String s = a.getString(name);
        if (s != null) {
            if (s.endsWith("%")) {
                String f = s.substring(0, s.length() - 1);
                return Float.parseFloat(f) / 100.0f;
            }
        }
        return defaultValue;
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs, theme);

        final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.ScaleDrawable);

        // Reset mDrawable to preserve old multiple-inflate behavior. This is
        // silly, but we have CTS tests that rely on it.
        mState.mDrawable = null;

        updateStateFromTypedArray(a);
        inflateChildElements(r, parser, attrs, theme);
        verifyRequiredAttributes(a);
        a.recycle();
    }

    @Override
    public void applyTheme(Theme t) {
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
                throw new RuntimeException(e);
            } finally {
                a.recycle();
            }
        }

        if (state.mDrawable != null && state.mDrawable.canApplyTheme()) {
            state.mDrawable.applyTheme(t);
        }
    }

    private void inflateChildElements(Resources r, XmlPullParser parser, AttributeSet attrs,
            Theme theme) throws XmlPullParserException, IOException {
        Drawable dr = null;
        int type;
        final int outerDepth = parser.getDepth();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type != XmlPullParser.START_TAG) {
                continue;
            }
            dr = Drawable.createFromXmlInner(r, parser, attrs, theme);
        }

        if (dr != null) {
            mState.mDrawable = dr;
            dr.setCallback(this);
        }
    }

    private void verifyRequiredAttributes(TypedArray a) throws XmlPullParserException {
        // If we're not waiting on a theme, verify required attributes.
        if (mState.mDrawable == null && (mState.mThemeAttrs == null
                || mState.mThemeAttrs[R.styleable.ScaleDrawable_drawable] == 0)) {
            throw new XmlPullParserException(a.getPositionDescription()
                    + ": <scale> tag requires a 'drawable' attribute or "
                    + "child tag defining a drawable");
        }
    }

    private void updateStateFromTypedArray(TypedArray a) {
        final ScaleState state = mState;

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mThemeAttrs = a.extractThemeAttrs();

        state.mScaleWidth = getPercent(
                a, R.styleable.ScaleDrawable_scaleWidth, state.mScaleWidth);
        state.mScaleHeight = getPercent(
                a, R.styleable.ScaleDrawable_scaleHeight, state.mScaleHeight);
        state.mGravity = a.getInt(R.styleable.ScaleDrawable_scaleGravity, state.mGravity);
        state.mUseIntrinsicSizeAsMin = a.getBoolean(
                R.styleable.ScaleDrawable_useIntrinsicSizeAsMinimum, state.mUseIntrinsicSizeAsMin);

        final Drawable dr = a.getDrawable(R.styleable.ScaleDrawable_drawable);
        if (dr != null) {
            state.mDrawable = dr;
            dr.setCallback(this);
        }
    }

    @Override
    public boolean canApplyTheme() {
        return (mState != null && mState.canApplyTheme()) || super.canApplyTheme();
    }

    // overrides from Drawable.Callback

    public void invalidateDrawable(Drawable who) {
        if (getCallback() != null) {
            getCallback().invalidateDrawable(this);
        }
    }

    public void scheduleDrawable(Drawable who, Runnable what, long when) {
        if (getCallback() != null) {
            getCallback().scheduleDrawable(this, what, when);
        }
    }

    public void unscheduleDrawable(Drawable who, Runnable what) {
        if (getCallback() != null) {
            getCallback().unscheduleDrawable(this, what);
        }
    }

    // overrides from Drawable

    @Override
    public void draw(Canvas canvas) {
        if (mState.mDrawable.getLevel() != 0)
            mState.mDrawable.draw(canvas);
    }

    @Override
    public int getChangingConfigurations() {
        return super.getChangingConfigurations()
                | mState.mChangingConfigurations
                | mState.mDrawable.getChangingConfigurations();
    }

    @Override
    public boolean getPadding(Rect padding) {
        // XXX need to adjust padding!
        return mState.mDrawable.getPadding(padding);
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        mState.mDrawable.setVisible(visible, restart);
        return super.setVisible(visible, restart);
    }

    @Override
    public void setAlpha(int alpha) {
        mState.mDrawable.setAlpha(alpha);
    }

    @Override
    public int getAlpha() {
        return mState.mDrawable.getAlpha();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        mState.mDrawable.setColorFilter(cf);
    }

    @Override
    public void setTintList(ColorStateList tint) {
        mState.mDrawable.setTintList(tint);
    }

    @Override
    public void setTintMode(Mode tintMode) {
        mState.mDrawable.setTintMode(tintMode);
    }

    @Override
    public int getOpacity() {
        return mState.mDrawable.getOpacity();
    }

    @Override
    public boolean isStateful() {
        return mState.mDrawable.isStateful();
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean changed = mState.mDrawable.setState(state);
        onBoundsChange(getBounds());
        return changed;
    }

    @Override
    protected boolean onLevelChange(int level) {
        mState.mDrawable.setLevel(level);
        onBoundsChange(getBounds());
        invalidateSelf();
        return true;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        final Rect r = mTmpRect;
        final boolean min = mState.mUseIntrinsicSizeAsMin;
        int level = getLevel();
        int w = bounds.width();
        if (mState.mScaleWidth > 0) {
            final int iw = min ? mState.mDrawable.getIntrinsicWidth() : 0;
            w -= (int) ((w - iw) * (10000 - level) * mState.mScaleWidth / 10000);
        }
        int h = bounds.height();
        if (mState.mScaleHeight > 0) {
            final int ih = min ? mState.mDrawable.getIntrinsicHeight() : 0;
            h -= (int) ((h - ih) * (10000 - level) * mState.mScaleHeight / 10000);
        }
        final int layoutDirection = getLayoutDirection();
        Gravity.apply(mState.mGravity, w, h, bounds, r, layoutDirection);

        if (w > 0 && h > 0) {
            mState.mDrawable.setBounds(r.left, r.top, r.right, r.bottom);
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return mState.mDrawable.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return mState.mDrawable.getIntrinsicHeight();
    }

    @Override
    public ConstantState getConstantState() {
        if (mState.canConstantState()) {
            mState.mChangingConfigurations = getChangingConfigurations();
            return mState;
        }
        return null;
    }

    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mState.mDrawable.mutate();
            mMutated = true;
        }
        return this;
    }

    /**
     * @hide
     */
    public void clearMutated() {
        super.clearMutated();
        mState.mDrawable.clearMutated();
        mMutated = false;
    }

    final static class ScaleState extends ConstantState {
        /** Constant used to disable scaling for a particular dimension. */
        private static final float DO_NOT_SCALE = -1.0f;

        int[] mThemeAttrs;
        int mChangingConfigurations;

        Drawable mDrawable;

        float mScaleWidth = DO_NOT_SCALE;
        float mScaleHeight = DO_NOT_SCALE;
        int mGravity = Gravity.LEFT;
        boolean mUseIntrinsicSizeAsMin = false;

        private boolean mCheckedConstantState;
        private boolean mCanConstantState;

        ScaleState(ScaleState orig, ScaleDrawable owner, Resources res) {
            if (orig != null) {
                mThemeAttrs = orig.mThemeAttrs;
                mChangingConfigurations = orig.mChangingConfigurations;
                if (res != null) {
                    mDrawable = orig.mDrawable.getConstantState().newDrawable(res);
                } else {
                    mDrawable = orig.mDrawable.getConstantState().newDrawable();
                }
                mDrawable.setCallback(owner);
                mDrawable.setLayoutDirection(orig.mDrawable.getLayoutDirection());
                mDrawable.setBounds(orig.mDrawable.getBounds());
                mDrawable.setLevel(orig.mDrawable.getLevel());
                mScaleWidth = orig.mScaleWidth;
                mScaleHeight = orig.mScaleHeight;
                mGravity = orig.mGravity;
                mUseIntrinsicSizeAsMin = orig.mUseIntrinsicSizeAsMin;
                mCheckedConstantState = mCanConstantState = true;
            }
        }

        @Override
        public boolean canApplyTheme() {
            return mThemeAttrs != null || (mDrawable != null && mDrawable.canApplyTheme())
                    || super.canApplyTheme();
        }

        @Override
        public Drawable newDrawable() {
            return new ScaleDrawable(this, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new ScaleDrawable(this, res);
        }

        @Override
        public int getChangingConfigurations() {
            return mChangingConfigurations;
        }

        boolean canConstantState() {
            if (!mCheckedConstantState) {
                mCanConstantState = mDrawable.getConstantState() != null;
                mCheckedConstantState = true;
            }

            return mCanConstantState;
        }

        @Override
        public int addAtlasableBitmaps(Collection<Bitmap> atlasList) {
            final ConstantState state = mDrawable.getConstantState();
            if (state != null) {
                return state.addAtlasableBitmaps(atlasList);
            }
            return 0;
        }
    }

    private ScaleDrawable(ScaleState state, Resources res) {
        mState = new ScaleState(state, this, res);
    }
}

