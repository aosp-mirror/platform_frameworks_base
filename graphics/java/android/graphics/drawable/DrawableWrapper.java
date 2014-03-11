/*
 * Copyright (C) 2014 The Android Open Source Project
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
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.graphics.Xfermode;

/**
 * A Drawable that wraps another Drawable.
 */
class DrawableWrapper extends Drawable implements Drawable.Callback {
    private WrapperState mWrapperState;

    /** Local drawable backed by its own constant state. */
    private Drawable mWrappedDrawable;

    private boolean mMutated;

    /** @hide */
    @Override
    public boolean isProjected() {
        return mWrappedDrawable.isProjected();
    }

    @Override
    public void setAutoMirrored(boolean mirrored) {
        mWrappedDrawable.setAutoMirrored(mirrored);
    }

    @Override
    public boolean isAutoMirrored() {
        return mWrappedDrawable.isAutoMirrored();
    }

    @Override
    public int getMinimumWidth() {
        return mWrappedDrawable.getMinimumWidth();
    }

    @Override
    public int getMinimumHeight() {
        return mWrappedDrawable.getMinimumHeight();
    }

    @Override
    public int getIntrinsicWidth() {
        return mWrappedDrawable.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return mWrappedDrawable.getIntrinsicHeight();
    }

    @Override
    public Drawable getCurrent() {
        return mWrappedDrawable.getCurrent();
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
        mWrappedDrawable.draw(canvas);
    }

    @Override
    public int getChangingConfigurations() {
        return mWrappedDrawable.getChangingConfigurations();
    }

    @Override
    public boolean getPadding(Rect padding) {
        return mWrappedDrawable.getPadding(padding);
    }

    @Override
    public Rect getDirtyBounds() {
        return mWrappedDrawable.getDirtyBounds();
    }

    /**
     * @hide
     */
    @Override
    public boolean supportsHotspots() {
        return mWrappedDrawable.supportsHotspots();
    }

    /**
     * @hide
     */
    @Override
    public void setHotspot(int id, float x, float y) {
        mWrappedDrawable.setHotspot(id, x, y);
    }

    /**
     * @hide
     */
    @Override
    public void removeHotspot(int id) {
        mWrappedDrawable.removeHotspot(id);
    }

    /**
     * @hide
     */
    @Override
    public void clearHotspots() {
        mWrappedDrawable.clearHotspots();
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        // Must call through to super().
        super.setVisible(visible, restart);
        return mWrappedDrawable.setVisible(visible, restart);
    }

    @Override
    public void setAlpha(int alpha) {
        mWrappedDrawable.setAlpha(alpha);
    }

    @Override
    public int getAlpha() {
        return mWrappedDrawable.getAlpha();
    }

    /** {@hide} */
    @Override
    public void setLayoutDirection(int layoutDirection) {
        mWrappedDrawable.setLayoutDirection(layoutDirection);
    }

    /** {@hide} */
    @Override
    public int getLayoutDirection() {
        return mWrappedDrawable.getLayoutDirection();
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        mWrappedDrawable.setColorFilter(cf);
    }

    @Override
    public ColorFilter getColorFilter() {
        return mWrappedDrawable.getColorFilter();
    }

    @Override
    public void setFilterBitmap(boolean filter) {
        mWrappedDrawable.setFilterBitmap(filter);
    }

    @Override
    public void setXfermode(Xfermode mode) {
        mWrappedDrawable.setXfermode(mode);
    }

    @Override
    public int getOpacity() {
        return mWrappedDrawable.getOpacity();
    }

    @Override
    public boolean isStateful() {
        return mWrappedDrawable.isStateful();
    }
    
    @Override
    public final boolean setState(int[] stateSet) {
        return super.setState(stateSet);
    }

    @Override
    public final int[] getState() {
        return super.getState();
    }

    @Override
    protected boolean onStateChange(int[] state) {
        // Don't override setState(), getState().
        return mWrappedDrawable.setState(state);
    }

    @Override
    protected boolean onLevelChange(int level) {
        // Don't override setLevel(), getLevel().
        return mWrappedDrawable.setLevel(level);
    }
    
    @Override
    public final void setBounds(int left, int top, int right, int bottom) {
        super.setBounds(left, top, right, bottom);
    }
    
    @Override
    public final void setBounds(Rect bounds) {
        super.setBounds(bounds);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        // Don't override setBounds(), getBounds().
        mWrappedDrawable.setBounds(bounds);
    }

    protected void setConstantState(WrapperState wrapperState, Resources res) {
        mWrapperState = wrapperState;

        // Load a new drawable from the constant state.
        if (wrapperState == null || wrapperState.mWrappedConstantState == null) {
            mWrappedDrawable = null;
        } else if (res != null) {
            mWrappedDrawable = wrapperState.mWrappedConstantState.newDrawable(res);
        } else {
            mWrappedDrawable = wrapperState.mWrappedConstantState.newDrawable();
        }
    }

    @Override
    public ConstantState getConstantState() {
        return mWrapperState;
    }

    @Override
    public Drawable mutate() {
        if (!mMutated) {
            mWrappedDrawable = mWrappedDrawable.mutate();
            mMutated = true;
        }
        return this;
    }

    /**
     * Sets the wrapped drawable and update the constant state.
     *
     * @param drawable
     * @param res
     */
    protected final void setDrawable(Drawable drawable, Resources res) {
        if (mWrappedDrawable != null) {
            mWrappedDrawable.setCallback(null);
        }

        mWrappedDrawable = drawable;

        if (drawable != null) {
            drawable.setCallback(this);

            mWrapperState.mWrappedConstantState = drawable.getConstantState();
        } else {
            mWrapperState.mWrappedConstantState = null;
        }
    }

    protected final Drawable getDrawable() {
        return mWrappedDrawable;
    }

    static abstract class WrapperState extends ConstantState {
        ConstantState mWrappedConstantState;

        WrapperState(WrapperState orig) {
            if (orig != null) {
                mWrappedConstantState = orig.mWrappedConstantState;
            }
        }

        @Override
        public int getChangingConfigurations() {
            return mWrappedConstantState.getChangingConfigurations();
        }
    }
}
