package com.android.server.status;

import android.graphics.drawable.Drawable;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;
import android.util.Log;

class FixedSizeDrawable extends Drawable {
    Drawable mDrawable;
    int mLeft;
    int mTop;
    int mRight;
    int mBottom;

    FixedSizeDrawable(Drawable that) {
        mDrawable = that;
    }

    public void setFixedBounds(int l, int t, int r, int b) {
        mLeft = l;
        mTop = t;
        mRight = r;
        mBottom = b;
    }

    public void setBounds(Rect bounds) {
        mDrawable.setBounds(mLeft, mTop, mRight, mBottom);
    }

    public void setBounds(int l, int t, int r, int b) {
        mDrawable.setBounds(mLeft, mTop, mRight, mBottom);
    }

    public void draw(Canvas canvas) {
        mDrawable.draw(canvas);
    }

    public int getOpacity() {
        return mDrawable.getOpacity();
    }

    public void setAlpha(int alpha) {
        mDrawable.setAlpha(alpha);
    }

    public void setColorFilter(ColorFilter cf) {
        mDrawable.setColorFilter(cf);
    }
}
