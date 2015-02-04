/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.server.policy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;

/**
 * A vertical linear layout.  However, instead of drawing the background
 * behnd the items, it draws the background outside the items based on the
 * padding.  If there isn't enough room to draw both, it clips the background
 * instead of the contents.
 */
public class RecentApplicationsBackground extends LinearLayout {
    private static final String TAG = "RecentApplicationsBackground";

    private boolean mBackgroundSizeChanged;
    private Drawable mBackground;
    private Rect mTmp0 = new Rect();
    private Rect mTmp1 = new Rect();

    public RecentApplicationsBackground(Context context) {
        this(context, null);
        init();
    }

    public RecentApplicationsBackground(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        mBackground = getBackground();
        setBackgroundDrawable(null);
        setPadding(0, 0, 0, 0);
        setGravity(Gravity.CENTER);
    }

    @Override
    protected boolean setFrame(int left, int top, int right, int bottom) {
        setWillNotDraw(false);
        if (mLeft != left || mRight != right || mTop != top || mBottom != bottom) {
            mBackgroundSizeChanged = true;
        }
        return super.setFrame(left, top, right, bottom);
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return who == mBackground || super.verifyDrawable(who);
    }

    @Override
    public void jumpDrawablesToCurrentState() {
        super.jumpDrawablesToCurrentState();
        if (mBackground != null) mBackground.jumpToCurrentState();
    }

    @Override
    protected void drawableStateChanged() {
        Drawable d = mBackground;
        if (d != null && d.isStateful()) {
            d.setState(getDrawableState());
        }
        super.drawableStateChanged();
    }

    @Override
    public void draw(Canvas canvas) {
        final Drawable background = mBackground;
        if (background != null) {
            if (mBackgroundSizeChanged) {
                mBackgroundSizeChanged = false;
                Rect chld = mTmp0;
                Rect bkg = mTmp1;
                mBackground.getPadding(bkg);
                getChildBounds(chld);
                // This doesn't clamp to this view's bounds, which is what we want,
                // so that the drawing is clipped.
                final int top = chld.top - bkg.top;
                final int bottom = chld.bottom + bkg.bottom;
                // The background here is a gradient that wants to
                // extend the full width of the screen (whatever that
                // may be).
                int left, right;
                if (false) {
                    // This limits the width of the drawable.
                    left = chld.left - bkg.left;
                    right = chld.right + bkg.right;
                } else {
                    // This expands it to full width.
                    left = 0;
                    right = getRight();
                }
                background.setBounds(left, top, right, bottom);
            }
        }
        mBackground.draw(canvas);

        if (false) {
            android.graphics.Paint p = new android.graphics.Paint();
            p.setColor(0x88ffff00);
            canvas.drawRect(background.getBounds(), p);
        }
        canvas.drawARGB((int)(0.75*0xff), 0, 0, 0);

        super.draw(canvas);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mBackground.setCallback(this);
        setWillNotDraw(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mBackground.setCallback(null);
    }
    
    private void getChildBounds(Rect r) {
        r.left = r.top = Integer.MAX_VALUE;
        r.bottom = r.right = Integer.MIN_VALUE;
        final int N = getChildCount();
        for (int i=0; i<N; i++) {
            View v = getChildAt(i);
            if (v.getVisibility() == View.VISIBLE) {
                r.left = Math.min(r.left, v.getLeft());
                r.top = Math.min(r.top, v.getTop());
                r.right = Math.max(r.right, v.getRight());
                r.bottom = Math.max(r.bottom, v.getBottom());
            }
        }
    }
}
