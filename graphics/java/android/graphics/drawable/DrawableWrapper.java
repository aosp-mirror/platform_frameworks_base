/*
 * Copyright (C) 2015 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Insets;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import java.io.IOException;
import java.util.Collection;

/**
 * Drawable container with only one child element.
 */
public abstract class DrawableWrapper extends Drawable implements Drawable.Callback {
    private DrawableWrapperState mState;
    private Drawable mDrawable;
    private boolean mMutated;

    DrawableWrapper(DrawableWrapperState state, Resources res) {
        mState = state;

        updateLocalState(res);
    }

    /**
     * Creates a new wrapper around the specified drawable.
     *
     * @param dr the drawable to wrap
     */
    public DrawableWrapper(@Nullable Drawable dr) {
        mState = null;
        mDrawable = dr;
    }

    /**
     * Initializes local dynamic properties from state. This should be called
     * after significant state changes, e.g. from the One True Constructor and
     * after inflating or applying a theme.
     */
    private void updateLocalState(Resources res) {
        if (mState != null && mState.mDrawableState != null) {
            final Drawable dr = mState.mDrawableState.newDrawable(res);
            setDrawable(dr);
        }
    }

    /**
     * Sets the wrapped drawable.
     *
     * @param dr the wrapped drawable
     */
    public void setDrawable(@Nullable Drawable dr) {
        if (mDrawable != null) {
            mDrawable.setCallback(null);
        }

        mDrawable = dr;

        if (dr != null) {
            dr.setCallback(this);

            // Only call setters for data that's stored in the base Drawable.
            dr.setVisible(isVisible(), true);
            dr.setState(getState());
            dr.setLevel(getLevel());
            dr.setBounds(getBounds());
            dr.setLayoutDirection(getLayoutDirection());

            if (mState != null) {
                mState.mDrawableState = dr.getConstantState();
            }
        }

        invalidateSelf();
    }

    /**
     * @return the wrapped drawable
     */
    @Nullable
    public Drawable getDrawable() {
        return mDrawable;
    }

    void updateStateFromTypedArray(TypedArray a) {
        final DrawableWrapperState state = mState;
        if (state == null) {
            return;
        }

        // Account for any configuration changes.
        state.mChangingConfigurations |= a.getChangingConfigurations();

        // Extract the theme attributes, if any.
        state.mThemeAttrs = a.extractThemeAttrs();

        // TODO: Consider using R.styleable.DrawableWrapper_drawable
    }

    @Override
    public void applyTheme(Resources.Theme t) {
        super.applyTheme(t);

        final DrawableWrapperState state = mState;
        if (state == null) {
            return;
        }

        if (mDrawable != null && mDrawable.canApplyTheme()) {
            mDrawable.applyTheme(t);
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
    public void draw(@NonNull Canvas canvas) {
        if (mDrawable != null) {
            mDrawable.draw(canvas);
        }
    }

    @Override
    public int getChangingConfigurations() {
        return super.getChangingConfigurations()
                | (mState != null ? mState.getChangingConfigurations() : 0)
                | mDrawable.getChangingConfigurations();
    }

    @Override
    public boolean getPadding(@NonNull Rect padding) {
        return mDrawable != null && mDrawable.getPadding(padding);
    }

    /** @hide */
    @Override
    public Insets getOpticalInsets() {
        return mDrawable != null ? mDrawable.getOpticalInsets() : Insets.NONE;
    }

    @Override
    public void setHotspot(float x, float y) {
        if (mDrawable != null) {
            mDrawable.setHotspot(x, y);
        }
    }

    @Override
    public void setHotspotBounds(int left, int top, int right, int bottom) {
        if (mDrawable != null) {
            mDrawable.setHotspotBounds(left, top, right, bottom);
        }
    }

    @Override
    public void getHotspotBounds(@NonNull Rect outRect) {
        if (mDrawable != null) {
            mDrawable.getHotspotBounds(outRect);
        } else {
            outRect.set(getBounds());
        }
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        final boolean superChanged = super.setVisible(visible, restart);
        final boolean changed = mDrawable != null && mDrawable.setVisible(visible, restart);
        return superChanged | changed;
    }

    @Override
    public void setAlpha(int alpha) {
        if (mDrawable != null) {
            mDrawable.setAlpha(alpha);
        }
    }

    @Override
    public int getAlpha() {
        return mDrawable != null ? mDrawable.getAlpha() : 255;
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        if (mDrawable != null) {
            mDrawable.setColorFilter(colorFilter);
        }
    }

    @Override
    public void setTintList(@Nullable ColorStateList tint) {
        if (mDrawable != null) {
            mDrawable.setTintList(tint);
        }
    }

    @Override
    public void setTintMode(@Nullable PorterDuff.Mode tintMode) {
        if (mDrawable != null) {
            mDrawable.setTintMode(tintMode);
        }
    }

    @Override
    public boolean onLayoutDirectionChanged(@View.ResolvedLayoutDir int layoutDirection) {
        return mDrawable != null && mDrawable.setLayoutDirection(layoutDirection);
    }

    @Override
    public int getOpacity() {
        return mDrawable != null ? mDrawable.getOpacity() : PixelFormat.TRANSPARENT;
    }

    @Override
    public boolean isStateful() {
        return mDrawable != null && mDrawable.isStateful();
    }

    @Override
    protected boolean onStateChange(int[] state) {
        if (mDrawable != null && mDrawable.isStateful()) {
            final boolean changed = mDrawable.setState(state);
            if (changed) {
                onBoundsChange(getBounds());
            }
            return changed;
        }
        return false;
    }

    @Override
    protected boolean onLevelChange(int level) {
        return mDrawable != null && mDrawable.setLevel(level);
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        if (mDrawable != null) {
            mDrawable.setBounds(bounds);
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return mDrawable != null ? mDrawable.getIntrinsicWidth() : -1;
    }

    @Override
    public int getIntrinsicHeight() {
        return mDrawable != null ? mDrawable.getIntrinsicHeight() : -1;
    }

    @Override
    public void getOutline(@NonNull Outline outline) {
        if (mDrawable != null) {
            mDrawable.getOutline(outline);
        } else {
            super.getOutline(outline);
        }
    }

    @Override
    @Nullable
    public ConstantState getConstantState() {
        if (mState != null && mState.canConstantState()) {
            mState.mChangingConfigurations = getChangingConfigurations();
            return mState;
        }
        return null;
    }

    @Override
    @NonNull
    public Drawable mutate() {
        if (!mMutated && super.mutate() == this) {
            mState = mutateConstantState();
            if (mDrawable != null) {
                mDrawable.mutate();
            }
            if (mState != null) {
                mState.mDrawableState = mDrawable != null ? mDrawable.getConstantState() : null;
            }
            mMutated = true;
        }
        return this;
    }

    /**
     * Mutates the constant state and returns the new state. Responsible for
     * updating any local copy.
     * <p>
     * This method should never call the super implementation; it should always
     * mutate and return its own constant state.
     *
     * @return the new state
     */
    DrawableWrapperState mutateConstantState() {
        return mState;
    }

    /**
     * @hide Only used by the framework for pre-loading resources.
     */
    public void clearMutated() {
        super.clearMutated();
        if (mDrawable != null) {
            mDrawable.clearMutated();
        }
        mMutated = false;
    }

    /**
     * Called during inflation to inflate the child element. The last valid
     * child element will take precedence over any other child elements or
     * explicit drawable attribute.
     */
    void inflateChildDrawable(Resources r, XmlPullParser parser, AttributeSet attrs,
            Resources.Theme theme) throws XmlPullParserException, IOException {
        // Seek to the first child element.
        Drawable dr = null;
        int type;
        final int outerDepth = parser.getDepth();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.START_TAG) {
                dr = Drawable.createFromXmlInner(r, parser, attrs, theme);
            }
        }

        if (dr != null) {
            setDrawable(dr);
        }
    }

    abstract static class DrawableWrapperState extends Drawable.ConstantState {
        int[] mThemeAttrs;
        int mChangingConfigurations;

        Drawable.ConstantState mDrawableState;

        DrawableWrapperState(DrawableWrapperState orig) {
            if (orig != null) {
                mThemeAttrs = orig.mThemeAttrs;
                mChangingConfigurations = orig.mChangingConfigurations;
                mDrawableState = orig.mDrawableState;
            }
        }

        @Override
        public boolean canApplyTheme() {
            return mThemeAttrs != null
                    || (mDrawableState != null && mDrawableState.canApplyTheme())
                    || super.canApplyTheme();
        }

        @Override
        public int addAtlasableBitmaps(Collection<Bitmap> atlasList) {
            final Drawable.ConstantState state = mDrawableState;
            if (state != null) {
                return state.addAtlasableBitmaps(atlasList);
            }
            return 0;
        }

        @Override
        public Drawable newDrawable() {
            return newDrawable(null);
        }

        @Override
        public abstract Drawable newDrawable(Resources res);

        @Override
        public int getChangingConfigurations() {
            return mChangingConfigurations
                    | (mDrawableState != null ? mDrawableState.getChangingConfigurations() : 0);
        }

        public boolean canConstantState() {
            return mDrawableState != null;
        }
    }
}
