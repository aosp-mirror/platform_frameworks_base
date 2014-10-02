/*
 * Copyright (C) 2007 The Android Open Source Project
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

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.graphics.PorterDuff.Mode;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.Resources.Theme;
import android.util.TypedValue;
import android.util.AttributeSet;
import android.util.Log;

import java.io.IOException;

/**
 * <p>
 * A Drawable that can rotate another Drawable based on the current level value.
 * The start and end angles of rotation can be controlled to map any circular
 * arc to the level values range.
 * <p>
 * It can be defined in an XML file with the <code>&lt;rotate&gt;</code> element.
 * For more information, see the guide to
 * <a href="{@docRoot}guide/topics/resources/animation-resource.html">Animation Resources</a>.
 *
 * @attr ref android.R.styleable#RotateDrawable_visible
 * @attr ref android.R.styleable#RotateDrawable_fromDegrees
 * @attr ref android.R.styleable#RotateDrawable_toDegrees
 * @attr ref android.R.styleable#RotateDrawable_pivotX
 * @attr ref android.R.styleable#RotateDrawable_pivotY
 * @attr ref android.R.styleable#RotateDrawable_drawable
 */
public class RotateDrawable extends Drawable implements Drawable.Callback {
    private static final float MAX_LEVEL = 10000.0f;

    private final RotateState mState;

    private boolean mMutated;

    /**
     * Create a new rotating drawable with an empty state.
     */
    public RotateDrawable() {
        this(null, null);
    }

    /**
     * Create a new rotating drawable with the specified state. A copy of
     * this state is used as the internal state for the newly created
     * drawable.
     *
     * @param rotateState the state for this drawable
     */
    private RotateDrawable(RotateState rotateState, Resources res) {
        mState = new RotateState(rotateState, this, res);
    }

    @Override
    public void draw(Canvas canvas) {
        final RotateState st = mState;
        final Drawable d = st.mDrawable;
        final Rect bounds = d.getBounds();
        final int w = bounds.right - bounds.left;
        final int h = bounds.bottom - bounds.top;
        final float px = st.mPivotXRel ? (w * st.mPivotX) : st.mPivotX;
        final float py = st.mPivotYRel ? (h * st.mPivotY) : st.mPivotY;

        final int saveCount = canvas.save();
        canvas.rotate(st.mCurrentDegrees, px + bounds.left, py + bounds.top);
        d.draw(canvas);
        canvas.restoreToCount(saveCount);
    }

    /**
     * Sets the drawable rotated by this RotateDrawable.
     *
     * @param drawable The drawable to rotate
     */
    public void setDrawable(Drawable drawable) {
        final Drawable oldDrawable = mState.mDrawable;
        if (oldDrawable != drawable) {
            if (oldDrawable != null) {
                oldDrawable.setCallback(null);
            }
            mState.mDrawable = drawable;
            if (drawable != null) {
                drawable.setCallback(this);
            }
        }
    }

    /**
     * @return The drawable rotated by this RotateDrawable
     */
    public Drawable getDrawable() {
        return mState.mDrawable;
    }

    @Override
    public int getChangingConfigurations() {
        return super.getChangingConfigurations()
                | mState.mChangingConfigurations
                | mState.mDrawable.getChangingConfigurations();
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

    /**
     * Sets the start angle for rotation.
     *
     * @param fromDegrees Starting angle in degrees
     *
     * @see #getFromDegrees()
     * @attr ref android.R.styleable#RotateDrawable_fromDegrees
     */
    public void setFromDegrees(float fromDegrees) {
        if (mState.mFromDegrees != fromDegrees) {
            mState.mFromDegrees = fromDegrees;
            invalidateSelf();
        }
    }

    /**
     * @return The starting angle for rotation in degrees
     *
     * @see #setFromDegrees(float)
     * @attr ref android.R.styleable#RotateDrawable_fromDegrees
     */
    public float getFromDegrees() {
        return mState.mFromDegrees;
    }

    /**
     * Sets the end angle for rotation.
     *
     * @param toDegrees Ending angle in degrees
     *
     * @see #getToDegrees()
     * @attr ref android.R.styleable#RotateDrawable_toDegrees
     */
    public void setToDegrees(float toDegrees) {
        if (mState.mToDegrees != toDegrees) {
            mState.mToDegrees = toDegrees;
            invalidateSelf();
        }
    }

    /**
     * @return The ending angle for rotation in degrees
     *
     * @see #setToDegrees(float)
     * @attr ref android.R.styleable#RotateDrawable_toDegrees
     */
    public float getToDegrees() {
        return mState.mToDegrees;
    }

    /**
     * Sets the X position around which the drawable is rotated.
     *
     * @param pivotX X position around which to rotate. If the X pivot is
     *            relative, the position represents a fraction of the drawable
     *            width. Otherwise, the position represents an absolute value in
     *            pixels.
     *
     * @see #setPivotXRelative(boolean)
     * @attr ref android.R.styleable#RotateDrawable_pivotX
     */
    public void setPivotX(float pivotX) {
        if (mState.mPivotX == pivotX) {
            mState.mPivotX = pivotX;
            invalidateSelf();
        }
    }

    /**
     * @return X position around which to rotate
     *
     * @see #setPivotX(float)
     * @attr ref android.R.styleable#RotateDrawable_pivotX
     */
    public float getPivotX() {
        return mState.mPivotX;
    }

    /**
     * Sets whether the X pivot value represents a fraction of the drawable
     * width or an absolute value in pixels.
     *
     * @param relative True if the X pivot represents a fraction of the drawable
     *            width, or false if it represents an absolute value in pixels
     *
     * @see #isPivotXRelative()
     */
    public void setPivotXRelative(boolean relative) {
        if (mState.mPivotXRel == relative) {
            mState.mPivotXRel = relative;
            invalidateSelf();
        }
    }

    /**
     * @return True if the X pivot represents a fraction of the drawable width,
     *         or false if it represents an absolute value in pixels
     *
     * @see #setPivotXRelative(boolean)
     */
    public boolean isPivotXRelative() {
        return mState.mPivotXRel;
    }

    /**
     * Sets the Y position around which the drawable is rotated.
     *
     * @param pivotY Y position around which to rotate. If the Y pivot is
     *            relative, the position represents a fraction of the drawable
     *            height. Otherwise, the position represents an absolute value
     *            in pixels.
     *
     * @see #getPivotY()
     * @attr ref android.R.styleable#RotateDrawable_pivotY
     */
    public void setPivotY(float pivotY) {
        if (mState.mPivotY == pivotY) {
            mState.mPivotY = pivotY;
            invalidateSelf();
        }
    }

    /**
     * @return Y position around which to rotate
     *
     * @see #setPivotY(float)
     * @attr ref android.R.styleable#RotateDrawable_pivotY
     */
    public float getPivotY() {
        return mState.mPivotY;
    }

    /**
     * Sets whether the Y pivot value represents a fraction of the drawable
     * height or an absolute value in pixels.
     *
     * @param relative True if the Y pivot represents a fraction of the drawable
     *            height, or false if it represents an absolute value in pixels
     *
     * @see #isPivotYRelative()
     */
    public void setPivotYRelative(boolean relative) {
        if (mState.mPivotYRel == relative) {
            mState.mPivotYRel = relative;
            invalidateSelf();
        }
    }

    /**
     * @return True if the Y pivot represents a fraction of the drawable height,
     *         or false if it represents an absolute value in pixels
     *
     * @see #setPivotYRelative(boolean)
     */
    public boolean isPivotYRelative() {
        return mState.mPivotYRel;
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
    public boolean getPadding(Rect padding) {
        return mState.mDrawable.getPadding(padding);
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        mState.mDrawable.setVisible(visible, restart);
        return super.setVisible(visible, restart);
    }

    @Override
    public boolean isStateful() {
        return mState.mDrawable.isStateful();
    }

    @Override
    protected boolean onStateChange(int[] state) {
        final boolean changed = mState.mDrawable.setState(state);
        onBoundsChange(getBounds());
        return changed;
    }

    @Override
    protected boolean onLevelChange(int level) {
        mState.mDrawable.setLevel(level);
        onBoundsChange(getBounds());

        mState.mCurrentDegrees = mState.mFromDegrees +
                (mState.mToDegrees - mState.mFromDegrees) *
                        (level / MAX_LEVEL);

        invalidateSelf();
        return true;
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        mState.mDrawable.setBounds(bounds.left, bounds.top,
                bounds.right, bounds.bottom);
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
    public void inflate(Resources r, XmlPullParser parser, AttributeSet attrs, Theme theme)
            throws XmlPullParserException, IOException {
        final TypedArray a = obtainAttributes(r, theme, attrs,
                com.android.internal.R.styleable.RotateDrawable);

        super.inflateWithAttributes(r, parser, a,
                com.android.internal.R.styleable.RotateDrawable_visible);

        TypedValue tv = a.peekValue(com.android.internal.R.styleable.RotateDrawable_pivotX);
        final boolean pivotXRel;
        final float pivotX;
        if (tv == null) {
            pivotXRel = true;
            pivotX = 0.5f;
        } else {
            pivotXRel = tv.type == TypedValue.TYPE_FRACTION;
            pivotX = pivotXRel ? tv.getFraction(1.0f, 1.0f) : tv.getFloat();
        }

        tv = a.peekValue(com.android.internal.R.styleable.RotateDrawable_pivotY);
        final boolean pivotYRel;
        final float pivotY;
        if (tv == null) {
            pivotYRel = true;
            pivotY = 0.5f;
        } else {
            pivotYRel = tv.type == TypedValue.TYPE_FRACTION;
            pivotY = pivotYRel ? tv.getFraction(1.0f, 1.0f) : tv.getFloat();
        }

        final float fromDegrees = a.getFloat(
                com.android.internal.R.styleable.RotateDrawable_fromDegrees, 0.0f);
        final float toDegrees = a.getFloat(
                com.android.internal.R.styleable.RotateDrawable_toDegrees, 360.0f);

        final int res = a.getResourceId(
                com.android.internal.R.styleable.RotateDrawable_drawable, 0);
        Drawable drawable = null;
        if (res > 0) {
            drawable = r.getDrawable(res, theme);
        }

        a.recycle();

        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT &&
               (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {

            if (type != XmlPullParser.START_TAG) {
                continue;
            }

            if ((drawable = Drawable.createFromXmlInner(r, parser, attrs, theme)) == null) {
                Log.w("drawable", "Bad element under <rotate>: "
                        + parser .getName());
            }
        }

        if (drawable == null) {
            Log.w("drawable", "No drawable specified for <rotate>");
        }

        final RotateState st = mState;
        st.mDrawable = drawable;
        st.mPivotXRel = pivotXRel;
        st.mPivotX = pivotX;
        st.mPivotYRel = pivotYRel;
        st.mPivotY = pivotY;
        st.mFromDegrees = fromDegrees;
        st.mCurrentDegrees = fromDegrees;
        st.mToDegrees = toDegrees;

        if (drawable != null) {
            drawable.setCallback(this);
        }
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
     * Represents the state of a rotation for a given drawable. The same
     * rotate drawable can be invoked with different states to drive several
     * rotations at the same time.
     */
    final static class RotateState extends Drawable.ConstantState {
        Drawable mDrawable;

        int mChangingConfigurations;

        boolean mPivotXRel;
        float mPivotX;
        boolean mPivotYRel;
        float mPivotY;

        float mFromDegrees;
        float mToDegrees;

        float mCurrentDegrees;

        private boolean mCanConstantState;
        private boolean mCheckedConstantState;

        public RotateState(RotateState orig, RotateDrawable owner, Resources res) {
            if (orig != null) {
                if (res != null) {
                    mDrawable = orig.mDrawable.getConstantState().newDrawable(res);
                } else {
                    mDrawable = orig.mDrawable.getConstantState().newDrawable();
                }
                mDrawable.setCallback(owner);
                mDrawable.setLayoutDirection(orig.mDrawable.getLayoutDirection());
                mDrawable.setBounds(orig.mDrawable.getBounds());
                mDrawable.setLevel(orig.mDrawable.getLevel());
                mPivotXRel = orig.mPivotXRel;
                mPivotX = orig.mPivotX;
                mPivotYRel = orig.mPivotYRel;
                mPivotY = orig.mPivotY;
                mFromDegrees = mCurrentDegrees = orig.mFromDegrees;
                mToDegrees = orig.mToDegrees;
                mCanConstantState = mCheckedConstantState = true;
            }
        }

        @Override
        public Drawable newDrawable() {
            return new RotateDrawable(this, null);
        }

        @Override
        public Drawable newDrawable(Resources res) {
            return new RotateDrawable(this, res);
        }

        @Override
        public int getChangingConfigurations() {
            return mChangingConfigurations;
        }

        public boolean canConstantState() {
            if (!mCheckedConstantState) {
                mCanConstantState = mDrawable.getConstantState() != null;
                mCheckedConstantState = true;
            }

            return mCanConstantState;
        }
    }
}
