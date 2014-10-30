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

/**
 * A Drawable that clips another Drawable based on this Drawable's current
 * level value.  You can control how much the child Drawable gets clipped in width
 * and height based on the level, as well as a gravity to control where it is
 * placed in its overall container.  Most often used to implement things like
 * progress bars, by increasing the drawable's level with {@link
 * android.graphics.drawable.Drawable#setLevel(int) setLevel()}.
 * <p class="note"><strong>Note:</strong> The drawable is clipped completely and not visible when
 * the level is 0 and fully revealed when the level is 10,000.</p>
 *
 * <p>It can be defined in an XML file with the <code>&lt;clip></code> element.  For more
 * information, see the guide to <a
 * href="{@docRoot}guide/topics/resources/drawable-resource.html">Drawable Resources</a>.</p>
 *
 * @attr ref android.R.styleable#ClipDrawable_clipOrientation
 * @attr ref android.R.styleable#ClipDrawable_gravity
 * @attr ref android.R.styleable#ClipDrawable_drawable
 */
public class ClipDrawable extends Drawable implements Drawable.Callback {
    private ClipState mState;
    private final Rect mTmpRect = new Rect();

    public static final int HORIZONTAL = 1;
    public static final int VERTICAL = 2;

    private boolean mMutated;

    ClipDrawable() {
        this(null, null);
    }

    /**
     * @param orientation Bitwise-or of {@link #HORIZONTAL} and/or {@link #VERTICAL}
     */
    public ClipDrawable(Drawable drawable, int gravity, int orientation) {
        this(null, null);

        mState.mDrawable = drawable;
        mState.mGravity = gravity;
        mState.mOrientation = orientation;

        if (drawable != null) {
            drawable.setCallback(this);
        }
    }

    @Override
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        super.inflate(r, parser, attrs, theme);

        final TypedArray a = obtainAttributes(r, theme, attrs, R.styleable.ClipDrawable);

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
                || mState.mThemeAttrs[R.styleable.ClipDrawable_drawable] == 0)) {
            throw new XmlPullParserException(a.getPositionDescription()
                    + ": <clip> tag requires a 'drawable' attribute or "
                    + "child tag defining a drawable");
        }
    }

    private void updateStateFromTypedArray(TypedArray a) {
        final ClipState state = mState;

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mThemeAttrs = a.extractThemeAttrs();

        state.mOrientation = a.getInt(R.styleable.ClipDrawable_clipOrientation, state.mOrientation);
        state.mGravity = a.getInt(R.styleable.ClipDrawable_gravity, state.mGravity);

        final Drawable dr = a.getDrawable(R.styleable.ClipDrawable_drawable);
        if (dr != null) {
            state.mDrawable = dr;
            dr.setCallback(this);
        }
    }

    @Override
    public void applyTheme(Theme t) {
        super.applyTheme(t);

        final ClipState state = mState;
        if (state == null || state.mThemeAttrs == null) {
            return;
        }

        final TypedArray a = t.resolveAttributes(state.mThemeAttrs, R.styleable.ClipDrawable);
        try {
            updateStateFromTypedArray(a);
            verifyRequiredAttributes(a);
        } catch (XmlPullParserException e) {
            throw new RuntimeException(e);
        } finally {
            a.recycle();
        }

        if (state.mDrawable != null && state.mDrawable.canApplyTheme()) {
            state.mDrawable.applyTheme(t);
        }
    }

    @Override
    public boolean canApplyTheme() {
        return (mState != null && mState.canApplyTheme()) || super.canApplyTheme();
    }

    // overrides from Drawable.Callback

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

    // overrides from Drawable

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
        return mState.mDrawable.setState(state);
    }

    @Override
    protected boolean onLevelChange(int level) {
        mState.mDrawable.setLevel(level);
        invalidateSelf();
        return true;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        mState.mDrawable.setBounds(bounds);
    }

    @Override
    public void draw(Canvas canvas) {

        if (mState.mDrawable.getLevel() == 0) {
            return;
        }

        final Rect r = mTmpRect;
        final Rect bounds = getBounds();
        int level = getLevel();
        int w = bounds.width();
        final int iw = 0; //mState.mDrawable.getIntrinsicWidth();
        if ((mState.mOrientation & HORIZONTAL) != 0) {
            w -= (w - iw) * (10000 - level) / 10000;
        }
        int h = bounds.height();
        final int ih = 0; //mState.mDrawable.getIntrinsicHeight();
        if ((mState.mOrientation & VERTICAL) != 0) {
            h -= (h - ih) * (10000 - level) / 10000;
        }
        final int layoutDirection = getLayoutDirection();
        Gravity.apply(mState.mGravity, w, h, bounds, r, layoutDirection);

        if (w > 0 && h > 0) {
            canvas.save();
            canvas.clipRect(r);
            mState.mDrawable.draw(canvas);
            canvas.restore();
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

    /** @hide */
    @Override
    public void setLayoutDirection(int layoutDirection) {
        mState.mDrawable.setLayoutDirection(layoutDirection);
        super.setLayoutDirection(layoutDirection);
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

    final static class ClipState extends ConstantState {
        int[] mThemeAttrs;
        int mChangingConfigurations;

        Drawable mDrawable;

        int mOrientation = HORIZONTAL;
        int mGravity = Gravity.LEFT;

        private boolean mCheckedConstantState;
        private boolean mCanConstantState;

        ClipState(ClipState orig, ClipDrawable owner, Resources res) {
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
                mOrientation = orig.mOrientation;
                mGravity = orig.mGravity;
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
            return new ClipDrawable(this, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new ClipDrawable(this, res);
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
    }

    private ClipDrawable(ClipState state, Resources res) {
        mState = new ClipState(state, this, res);
    }
}

