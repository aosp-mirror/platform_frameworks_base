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

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.*;
import android.util.AttributeSet;
import android.util.Log;

import java.io.IOException;

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
public class InsetDrawable extends Drawable implements Drawable.Callback
{
    // Most of this is copied from ScaleDrawable.
    private InsetState mInsetState;
    private final Rect mTmpRect = new Rect();
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
        
        mInsetState.mDrawable = drawable;
        mInsetState.mInsetLeft = insetLeft;
        mInsetState.mInsetTop = insetTop;
        mInsetState.mInsetRight = insetRight;
        mInsetState.mInsetBottom = insetBottom;
        
        if (drawable != null) {
            drawable.setCallback(this);
        }
    }
    
    @Override public void inflate(Resources r, XmlPullParser parser,
                                  AttributeSet attrs)
    throws XmlPullParserException, IOException {
        int type;
        
        TypedArray a = r.obtainAttributes(attrs,
                com.android.internal.R.styleable.InsetDrawable);

        super.inflateWithAttributes(r, parser, a,
                com.android.internal.R.styleable.InsetDrawable_visible);

        int drawableRes = a.getResourceId(com.android.internal.R.styleable.
                                    InsetDrawable_drawable, 0);

        int inLeft = a.getDimensionPixelOffset(com.android.internal.R.styleable.
                                    InsetDrawable_insetLeft, 0);
        int inTop = a.getDimensionPixelOffset(com.android.internal.R.styleable.
                                    InsetDrawable_insetTop, 0);
        int inRight = a.getDimensionPixelOffset(com.android.internal.R.styleable.
                                    InsetDrawable_insetRight, 0);
        int inBottom = a.getDimensionPixelOffset(com.android.internal.R.styleable.
                                    InsetDrawable_insetBottom, 0);

        a.recycle();

        Drawable dr;
        if (drawableRes != 0) {
            dr = r.getDrawable(drawableRes);
        } else {
            while ((type=parser.next()) == XmlPullParser.TEXT) {
            }
            if (type != XmlPullParser.START_TAG) {
                throw new XmlPullParserException(
                        parser.getPositionDescription()
                        + ": <inset> tag requires a 'drawable' attribute or "
                        + "child tag defining a drawable");
            }
            dr = Drawable.createFromXmlInner(r, parser, attrs);
        }

        if (dr == null) {
            Log.w("drawable", "No drawable specified for <inset>");
        }

        mInsetState.mDrawable = dr;
        mInsetState.mInsetLeft = inLeft;
        mInsetState.mInsetRight = inRight;
        mInsetState.mInsetTop = inTop;
        mInsetState.mInsetBottom = inBottom;

        if (dr != null) {
            dr.setCallback(this);
        }
    }

    // overrides from Drawable.Callback

    public void invalidateDrawable(Drawable who) {
        final Callback callback = getCallback();
        if (callback != null) {
            callback.invalidateDrawable(this);
        }
    }

    public void scheduleDrawable(Drawable who, Runnable what, long when) {
        final Callback callback = getCallback();
        if (callback != null) {
            callback.scheduleDrawable(this, what, when);
        }
    }

    public void unscheduleDrawable(Drawable who, Runnable what) {
        final Callback callback = getCallback();
        if (callback != null) {
            callback.unscheduleDrawable(this, what);
        }
    }

    // overrides from Drawable

    @Override
    public void draw(Canvas canvas) {
        mInsetState.mDrawable.draw(canvas);
    }

    @Override
    public int getChangingConfigurations() {
        return super.getChangingConfigurations()
                | mInsetState.mChangingConfigurations
                | mInsetState.mDrawable.getChangingConfigurations();
    }
    
    @Override
    public boolean getPadding(Rect padding) {
        boolean pad = mInsetState.mDrawable.getPadding(padding);

        padding.left += mInsetState.mInsetLeft;
        padding.right += mInsetState.mInsetRight;
        padding.top += mInsetState.mInsetTop;
        padding.bottom += mInsetState.mInsetBottom;

        if (pad || (mInsetState.mInsetLeft | mInsetState.mInsetRight | 
                    mInsetState.mInsetTop | mInsetState.mInsetBottom) != 0) {
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        mInsetState.mDrawable.setVisible(visible, restart);
        return super.setVisible(visible, restart);
    }

    @Override
    public void setAlpha(int alpha) {
        mInsetState.mDrawable.setAlpha(alpha);
    }

    @Override
    public int getAlpha() {
        return mInsetState.mDrawable.getAlpha();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        mInsetState.mDrawable.setColorFilter(cf);
    }

    /** {@hide} */
    @Override
    public void setLayoutDirection(int layoutDirection) {
        mInsetState.mDrawable.setLayoutDirection(layoutDirection);
    }

    @Override
    public int getOpacity() {
        return mInsetState.mDrawable.getOpacity();
    }
    
    @Override
    public boolean isStateful() {
        return mInsetState.mDrawable.isStateful();
    }

    @Override
    protected boolean onStateChange(int[] state) {
        boolean changed = mInsetState.mDrawable.setState(state);
        onBoundsChange(getBounds());
        return changed;
    }
    
    @Override
    protected void onBoundsChange(Rect bounds) {
        final Rect r = mTmpRect;
        r.set(bounds);

        r.left += mInsetState.mInsetLeft;
        r.top += mInsetState.mInsetTop;
        r.right -= mInsetState.mInsetRight;
        r.bottom -= mInsetState.mInsetBottom;

        mInsetState.mDrawable.setBounds(r.left, r.top, r.right, r.bottom);
    }

    @Override
    public int getIntrinsicWidth() {
        return mInsetState.mDrawable.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return mInsetState.mDrawable.getIntrinsicHeight();
    }

    @Override
    public ConstantState getConstantState() {
        if (mInsetState.canConstantState()) {
            mInsetState.mChangingConfigurations = getChangingConfigurations();
            return mInsetState;
        }
        return null;
    }

    @Override
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mInsetState.mDrawable.mutate();
            mMutated = true;
        }
        return this;
    }

    /**
     * Returns the drawable wrapped by this InsetDrawable. May be null.
     */
    public Drawable getDrawable() {
        return mInsetState.mDrawable;
    }

    final static class InsetState extends ConstantState {
        Drawable mDrawable;
        int mChangingConfigurations;

        int mInsetLeft;
        int mInsetTop;
        int mInsetRight;
        int mInsetBottom;

        boolean mCheckedConstantState;
        boolean mCanConstantState;

        InsetState(InsetState orig, InsetDrawable owner, Resources res) {
            if (orig != null) {
                if (res != null) {
                    mDrawable = orig.mDrawable.getConstantState().newDrawable(res);
                } else {
                    mDrawable = orig.mDrawable.getConstantState().newDrawable();
                }
                mDrawable.setCallback(owner);
                mDrawable.setLayoutDirection(orig.mDrawable.getLayoutDirection());
                mInsetLeft = orig.mInsetLeft;
                mInsetTop = orig.mInsetTop;
                mInsetRight = orig.mInsetRight;
                mInsetBottom = orig.mInsetBottom;
                mCheckedConstantState = mCanConstantState = true;
            }
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
    }

    private InsetDrawable(InsetState state, Resources res) {
        mInsetState = new InsetState(state, this, res);
    }
}

