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

import android.graphics.*;

/**
 * 
 * A resizeable bitmap, with stretchable areas that you define. This type of image
 * is defined in a .png file with a special format, described in <a link="../../../resources.html#ninepatch">
 * Resources</a>.
 *
 */
public class NinePatchDrawable extends Drawable {

    public NinePatchDrawable(Bitmap bitmap, byte[] chunk,
                             Rect padding, String srcName) {
        this(new NinePatchState(new NinePatch(bitmap, chunk, srcName), padding));
    }
    
    public NinePatchDrawable(NinePatch patch) {
        this(new NinePatchState(patch, null));
    }

    // overrides

    @Override
    public void draw(Canvas canvas) {
        mNinePatch.draw(canvas, getBounds(), mPaint);
    }

    @Override
    public int getChangingConfigurations() {
        return super.getChangingConfigurations()
                | mNinePatchState.mChangingConfigurations;
    }
    
    @Override
    public boolean getPadding(Rect padding) {
        padding.set(mPadding);
        return true;
    }

    @Override
    public void setAlpha(int alpha) {
        getPaint().setAlpha(alpha);
    }
    
    @Override
    public void setColorFilter(ColorFilter cf) {
        getPaint().setColorFilter(cf);
    }

    @Override
    public void setDither(boolean dither) {
        getPaint().setDither(dither);
    }

    public Paint getPaint() {
        if (mPaint == null) {
            mPaint = new Paint();
        }
        return mPaint;
    }

    /**
     * Retrieves the width of the source .png file (before resizing).
     */
    @Override
    public int getIntrinsicWidth() {
        return mNinePatch.getWidth();
    }

    /**
     * Retrieves the height of the source .png file (before resizing).
     */
    @Override
    public int getIntrinsicHeight() {
        return mNinePatch.getHeight();
    }

    @Override
    public int getMinimumWidth() {
        return mNinePatch.getWidth();
    }

    @Override
    public int getMinimumHeight() {
        return mNinePatch.getHeight();
    }

    /**
     * Returns a {@link android.graphics.PixelFormat graphics.PixelFormat} value of OPAQUE or TRANSLUCENT.
     */
    @Override
    public int getOpacity() {
        return mNinePatch.hasAlpha() || (mPaint != null && mPaint.getAlpha() < 255)
            ? PixelFormat.TRANSLUCENT : PixelFormat.OPAQUE;
    }

    @Override
    public Region getTransparentRegion() {
        return mNinePatch.getTransparentRegion(getBounds());
    }
    
    @Override
    public ConstantState getConstantState() {
        mNinePatchState.mChangingConfigurations = super.getChangingConfigurations();
        return mNinePatchState;
    }

    final static class NinePatchState extends ConstantState {
        NinePatchState(NinePatch ninePatch, Rect padding)
        {
            mNinePatch = ninePatch;
            mPadding = padding;
        }

        @Override
        public Drawable newDrawable()
        {
            return new NinePatchDrawable(this);
        }
        
        @Override
        public int getChangingConfigurations() {
            return mChangingConfigurations;
        }

        final NinePatch mNinePatch;
        final Rect      mPadding;
        int             mChangingConfigurations;
    }

    private NinePatchDrawable(NinePatchState state) {
        mNinePatchState = state;
        mNinePatch = state.mNinePatch;
        mPadding = state.mPadding;
    }

    private final NinePatchState    mNinePatchState;
    private final NinePatch         mNinePatch;
    private final Rect              mPadding;
    private Paint                   mPaint;
}

