/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.annotation.NonNull;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.Resources.Theme;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff.Mode;
import android.graphics.drawable.Drawable.ConstantState;
import android.graphics.Rect;
import android.util.AttributeSet;

import java.io.IOException;
import java.util.Collection;

/**
 * A Drawable that insets another Drawable by a specified distance.
 * This is used when a View needs a background that is smaller than
 * the View's actual bounds.
 *
 * <p>It can be defined in an XML file with the <code>&lt;inset></code> element. For more
 * information, see the guide to <a
 * href="{@docRoot}guide/topics/resources/drawable-resource.html">Drawable Resources</a>.</p>
 *
 * @attr ref android.R.styleable#InsetDrawable_visible
 * @attr ref android.R.styleable#InsetDrawable_drawable
 * @attr ref android.R.styleable#InsetDrawable_insetLeft
 * @attr ref android.R.styleable#InsetDrawable_insetRight
 * @attr ref android.R.styleable#InsetDrawable_insetTop
 * @attr ref android.R.styleable#InsetDrawable_insetBottom
 */
public class InsetDrawable extends Drawable implements Drawable.Callback {
    private final Rect mTmpRect = new Rect();

    private final InsetState mState;

    private boolean mMutated;

    /*package*/ InsetDrawable() {
        this(null, null);
    }

    public InsetDrawable(Drawable drawable, int inset) {
        this(drawable, inset, inset, inset, inset);
    }

    public InsetDrawable(Drawable drawable, int insetLeft, int insetTop,
                         int insetRight, int insetBottom) {
        this(null, null);

        mState.mDrawable = drawable;
        mState.mInsetLeft = insetLeft;
        mState.mInsetTop = insetTop;
        mState.mInsetRight = insetRight;
        mState.mInsetBottom = insetBottom;

        if (drawable != null) {
            drawable.setCallback(this);
        }
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.InsetDrawable);
        super.inflateWithAttributes(r, parser, a, R.styleable.InsetDrawable_visible);

        // Reset mDrawable to preserve old multiple-inflate behavior. This is
        // silly, but we have CTS tests that rely on it.
        mState.mDrawable = null;

        updateStateFromTypedArray(a);
        inflateChildElements(r, parser, attrs, theme);
        verifyRequiredAttributes(a);
        a.recycle();
    }

    private void inflateChildElements(Resources r, XmlPullParser parser, AttributeSet attrs,
            Theme theme) throws XmlPullParserException, IOException {
        // Load inner XML elements.
        if (mState.mDrawable == null) {
            int type;
            while ((type=parser.next()) == XmlPullParser.TEXT) {
            }
            if (type != XmlPullParser.START_TAG) {
                throw new XmlPullParserException(
                        parser.getPositionDescription()
                                + ": <inset> tag requires a 'drawable' attribute or "
                                + "child tag defining a drawable");
            }
            final Drawable dr = Drawable.createFromXmlInner(r, parser, attrs, theme);
            mState.mDrawable = dr;
            dr.setCallback(this);
        }
    }

    private void verifyRequiredAttributes(TypedArray a) throws XmlPullParserException {
        // If we're not waiting on a theme, verify required attributes.
        if (mState.mDrawable == null && (mState.mThemeAttrs == null
                || mState.mThemeAttrs[R.styleable.InsetDrawable_drawable] == 0)) {
            throw new XmlPullParserException(a.getPositionDescription()
                    + ": <inset> tag requires a 'drawable' attribute or "
                    + "child tag defining a drawable");
        }
    }

    private void updateStateFromTypedArray(TypedArray a) throws XmlPullParserException {
        final InsetState state = mState;

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mThemeAttrs = a.extractThemeAttrs();

        final int N = a.getIndexCount();
        for (int i = 0; i < N; i++) {
            final int attr = a.getIndex(i);
            switch (attr) {
                case R.styleable.InsetDrawable_drawable:
                    final Drawable dr = a.getDrawable(attr);
                    if (dr != null) {
                        state.mDrawable = dr;
                        dr.setCallback(this);
                    }
                    break;
                case R.styleable.InsetDrawable_inset:
                    final int inset = a.getDimensionPixelOffset(attr, Integer.MIN_VALUE);
                    if (inset != Integer.MIN_VALUE) {
                        state.mInsetLeft = inset;
                        state.mInsetTop = inset;
                        state.mInsetRight = inset;
                        state.mInsetBottom = inset;
                    }
                    break;
                case R.styleable.InsetDrawable_insetLeft:
                    state.mInsetLeft = a.getDimensionPixelOffset(attr, state.mInsetLeft);
                    break;
                case R.styleable.InsetDrawable_insetTop:
                    state.mInsetTop = a.getDimensionPixelOffset(attr, state.mInsetTop);
                    break;
                case R.styleable.InsetDrawable_insetRight:
                    state.mInsetRight = a.getDimensionPixelOffset(attr, state.mInsetRight);
                    break;
                case R.styleable.InsetDrawable_insetBottom:
                    state.mInsetBottom = a.getDimensionPixelOffset(attr, state.mInsetBottom);
                    break;
            }
        }
    }

    @Override
    public void applyTheme(Theme t) {
        super.applyTheme(t);

        final InsetState state = mState;
        if (state == null) {
            return;
        }

        if (state.mThemeAttrs != null) {
            final TypedArray a = t.resolveAttributes(state.mThemeAttrs, R.styleable.InsetDrawable);
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

    @Override
    public boolean canApplyTheme() {
        return (mState != null && mState.canApplyTheme()) || super.canApplyTheme();
    }

    @Override
    public void invalidateDrawable(Drawable who) {
        final Callback callback = getCallback();
        if (callback != null) {
            callback.invalidateDrawable(this);
        }
    }

    @Override
    public void scheduleDrawable(Drawable who, Runnable what, long when) {
        final Callback callback = getCallback();
        if (callback != null) {
            callback.scheduleDrawable(this, what, when);
        }
    }

    @Override
    public void unscheduleDrawable(Drawable who, Runnable what) {
        final Callback callback = getCallback();
        if (callback != null) {
            callback.unscheduleDrawable(this, what);
        }
    }

    @Override
    public void draw(Canvas canvas) {
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
        boolean pad = mState.mDrawable.getPadding(padding);

        padding.left += mState.mInsetLeft;
        padding.right += mState.mInsetRight;
        padding.top += mState.mInsetTop;
        padding.bottom += mState.mInsetBottom;

        return pad || (mState.mInsetLeft | mState.mInsetRight |
                mState.mInsetTop | mState.mInsetBottom) != 0;
    }

    /** @hide */
    @Override
    public Insets getOpticalInsets() {
        final Insets contentInsets = super.getOpticalInsets();
        return Insets.of(contentInsets.left + mState.mInsetLeft,
                contentInsets.top + mState.mInsetTop,
                contentInsets.right + mState.mInsetRight,
                contentInsets.bottom + mState.mInsetBottom);
    }

    @Override
    public void setHotspot(float x, float y) {
        mState.mDrawable.setHotspot(x, y);
    }

    @Override
    public void setHotspotBounds(int left, int top, int right, int bottom) {
        mState.mDrawable.setHotspotBounds(left, top, right, bottom);
    }

    /** @hide */
    @Override
    public void getHotspotBounds(Rect outRect) {
        mState.mDrawable.getHotspotBounds(outRect);
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

    /** {@hide} */
    @Override
    public void setLayoutDirection(int layoutDirection) {
        mState.mDrawable.setLayoutDirection(layoutDirection);
    }

    @Override
    public int getOpacity() {
        final InsetState state = mState;
        final int opacity = state.mDrawable.getOpacity();
        if (opacity == PixelFormat.OPAQUE && (state.mInsetLeft > 0 || state.mInsetTop > 0
                || state.mInsetRight > 0 || state.mInsetBottom > 0)) {
            return PixelFormat.TRANSLUCENT;
        }
        return opacity;
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
        return mState.mDrawable.setLevel(level);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        final Rect r = mTmpRect;
        r.set(bounds);

        r.left += mState.mInsetLeft;
        r.top += mState.mInsetTop;
        r.right -= mState.mInsetRight;
        r.bottom -= mState.mInsetBottom;

        mState.mDrawable.setBounds(r.left, r.top, r.right, r.bottom);
    }

    @Override
    public int getIntrinsicWidth() {
        return mState.mDrawable.getIntrinsicWidth()
                + mState.mInsetLeft + mState.mInsetRight;
    }

    @Override
    public int getIntrinsicHeight() {
        return mState.mDrawable.getIntrinsicHeight()
                + mState.mInsetTop + mState.mInsetBottom;
    }

    @Override
    public void getOutline(@NonNull Outline outline) {
        mState.mDrawable.getOutline(outline);
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

    /**
     * Returns the drawable wrapped by this InsetDrawable. May be null.
     */
    public Drawable getDrawable() {
        return mState.mDrawable;
    }

    final static class InsetState extends ConstantState {
        int[] mThemeAttrs;
        int mChangingConfigurations;

        Drawable mDrawable;

        int mInsetLeft = 0;
        int mInsetTop = 0;
        int mInsetRight = 0;
        int mInsetBottom = 0;

        private boolean mCheckedConstantState;
        private boolean mCanConstantState;

        InsetState(InsetState orig, InsetDrawable owner, Resources res) {
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
                mInsetLeft = orig.mInsetLeft;
                mInsetTop = orig.mInsetTop;
                mInsetRight = orig.mInsetRight;
                mInsetBottom = orig.mInsetBottom;
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
            return new InsetDrawable(this, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new InsetDrawable(this, res);
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

    private InsetDrawable(InsetState state, Resources res) {
        mState = new InsetState(state, this, res);
    }
}

