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
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import com.android.systemui.R;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;

/**
 * A view that displays a user image cropped to a circle with a frame.
 */
public class UserAvatarView extends View {

    private int mActiveFrameColor;
    private int mFrameColor;
    private float mFrameWidth;
    private float mFramePadding;
    private Bitmap mBitmap;
    private Drawable mDrawable;

    private final Paint mFramePaint = new Paint();
    private final Paint mBitmapPaint = new Paint();
    private final Matrix mDrawMatrix = new Matrix();

    private float mScale = 1;

    public UserAvatarView(Context context, AttributeSet attrs,
            int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.UserAvatarView, defStyleAttr, defStyleRes);
        final int N = a.getIndexCount();
        for (int i = 0; i < N; i++) {
            int attr = a.getIndex(i);
            switch (attr) {
                case R.styleable.UserAvatarView_frameWidth:
                    setFrameWidth(a.getDimension(attr, 0));
                    break;
                case R.styleable.UserAvatarView_framePadding:
                    setFramePadding(a.getDimension(attr, 0));
                    break;
                case R.styleable.UserAvatarView_activeFrameColor:
                    setActiveFrameColor(a.getColor(attr, 0));
                    break;
                case R.styleable.UserAvatarView_frameColor:
                    setFrameColor(a.getColor(attr, 0));
                    break;
            }
        }
        a.recycle();

        mFramePaint.setAntiAlias(true);
        mFramePaint.setStyle(Paint.Style.STROKE);
        mBitmapPaint.setAntiAlias(true);
    }

    public UserAvatarView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public UserAvatarView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public UserAvatarView(Context context) {
        this(context, null);
    }

    public void setBitmap(Bitmap bitmap) {
        setDrawable(null);
        mBitmap = bitmap;
        if (mBitmap != null) {
            mBitmapPaint.setShader(new BitmapShader(
                    bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        } else {
            mBitmapPaint.setShader(null);
        }
        configureBounds();
        invalidate();
    }

    public void setFrameColor(int frameColor) {
        mFrameColor = frameColor;
        invalidate();
    }

    public void setActiveFrameColor(int activeFrameColor) {
        mActiveFrameColor = activeFrameColor;
        invalidate();
    }

    public void setFrameWidth(float frameWidth) {
        mFrameWidth = frameWidth;
        invalidate();
    }

    public void setFramePadding(float framePadding) {
        mFramePadding = framePadding;
        invalidate();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        configureBounds();
    }

    public void configureBounds() {
        int vwidth = getWidth() - mPaddingLeft - mPaddingRight;
        int vheight = getHeight() - mPaddingTop - mPaddingBottom;

        int dwidth;
        int dheight;
        if (mBitmap != null) {
            dwidth = mBitmap.getWidth();
            dheight = mBitmap.getHeight();
        } else if (mDrawable != null) {
            vwidth -= 2 * (mFrameWidth - 1);
            vheight -= 2 * (mFrameWidth - 1);
            dwidth = vwidth;
            dheight = vheight;
            mDrawable.setBounds(0, 0, dwidth, dheight);
        } else {
            return;
        }

        float scale;
        float dx;
        float dy;

        scale = Math.min((float) vwidth / (float) dwidth,
                (float) vheight / (float) dheight);

        dx = (int) ((vwidth - dwidth * scale) * 0.5f + 0.5f);
        dy = (int) ((vheight - dheight * scale) * 0.5f + 0.5f);

        mDrawMatrix.setScale(scale, scale);
        mDrawMatrix.postTranslate(dx, dy);
        mScale = scale;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int frameColor = isActivated() ? mActiveFrameColor : mFrameColor;
        float halfW = getWidth() / 2f;
        float halfH = getHeight() / 2f;
        float halfSW = Math.min(halfH, halfW);
        if (mBitmap != null && mScale > 0) {
            int saveCount = canvas.getSaveCount();
            canvas.save();
            canvas.translate(mPaddingLeft, mPaddingTop);
            canvas.concat(mDrawMatrix);
            float halfBW = mBitmap.getWidth() / 2f;
            float halfBH = mBitmap.getHeight() / 2f;
            float halfBSW = Math.min(halfBH, halfBW);
            canvas.drawCircle(halfBW, halfBH, halfBSW - mFrameWidth / mScale + 1, mBitmapPaint);
            canvas.restoreToCount(saveCount);
        } else if (mDrawable != null && mScale > 0) {
            int saveCount = canvas.getSaveCount();
            canvas.save();
            canvas.translate(mPaddingLeft, mPaddingTop);
            canvas.translate(mFrameWidth - 1, mFrameWidth - 1);
            canvas.concat(mDrawMatrix);
            mDrawable.draw(canvas);
            canvas.restoreToCount(saveCount);
        }
        if (frameColor != 0) {
            mFramePaint.setColor(frameColor);
            mFramePaint.setStrokeWidth(mFrameWidth);
            canvas.drawCircle(halfW, halfH, halfSW + (mFramePadding - mFrameWidth) / 2f,
                    mFramePaint);
        }
    }

    public void setDrawable(Drawable d) {
        if (mDrawable != null) {
            mDrawable.setCallback(null);
            unscheduleDrawable(mDrawable);
        }
        mDrawable = d;
        if (d != null) {
            d.setCallback(this);
            if (d.isStateful()) {
                d.setState(getDrawableState());
            }
            d.setLayoutDirection(getLayoutDirection());
            configureBounds();
        }
        if (d != null) {
            mBitmap = null;
        }
        configureBounds();
        invalidate();
    }

    @Override
    public void invalidateDrawable(Drawable dr) {
        if (dr == mDrawable) {
            invalidate();
        } else {
            super.invalidateDrawable(dr);
        }
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return who == mDrawable || super.verifyDrawable(who);
    }

    @Override
    protected void drawableStateChanged() {
        super.drawableStateChanged();
        if (mDrawable != null && mDrawable.isStateful()) {
            mDrawable.setState(getDrawableState());
        }
    }
}
