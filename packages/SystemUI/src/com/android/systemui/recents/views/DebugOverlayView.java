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

package com.android.systemui.recents.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.Pair;
import android.view.View;
import android.widget.FrameLayout;

import java.util.ArrayList;

/**
 * A full screen overlay layer that allows us to draw views from throughout the system on the top
 * most layer.
 */
public class DebugOverlayView extends FrameLayout {

    final static int sCornerRectSize = 50;

    ArrayList<Pair<Rect, Integer>> mRects = new ArrayList<Pair<Rect, Integer>>();
    Paint mDebugOutline = new Paint();
    Paint mTmpPaint = new Paint();
    boolean mEnabled = true;

    public DebugOverlayView(Context context) {
        super(context);
        mDebugOutline.setColor(0xFFff0000);
        mDebugOutline.setStyle(Paint.Style.STROKE);
        mDebugOutline.setStrokeWidth(8f);
        setWillNotDraw(false);
    }

    /** Enables the debug overlay drawing. */
    public void enable() {
        mEnabled = true;
        invalidate();
    }

    /** Disables the debug overlay drawing. */
    public void disable() {
        mEnabled = false;
        invalidate();
    }

    /** Clears all debug rects. */
    public void clear() {
        mRects.clear();
    }

    /** Adds a rect to be drawn. */
    void addRect(Rect r, int color) {
        mRects.add(new Pair<Rect, Integer>(r, color));
        invalidate();
    }

    /** Adds a view's global rect to be drawn. */
    void addViewRect(View v, int color) {
        Rect vr = new Rect();
        v.getGlobalVisibleRect(vr);
        mRects.add(new Pair<Rect, Integer>(vr, color));
        invalidate();
    }

    /** Adds a rect, relative to a given view to be drawn. */
    void addRectRelativeToView(View v, Rect r, int color) {
        Rect vr = new Rect();
        v.getGlobalVisibleRect(vr);
        r.offsetTo(vr.left, vr.top);
        mRects.add(new Pair<Rect, Integer>(r, color));
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        addRect(new Rect(0, 0, sCornerRectSize, sCornerRectSize), 0xFFff0000);
        addRect(new Rect(getMeasuredWidth() - sCornerRectSize, getMeasuredHeight() - sCornerRectSize,
                getMeasuredWidth(), getMeasuredHeight()), 0xFFff0000);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mEnabled) {
            // Draw the outline
            canvas.drawRect(0, 0, getMeasuredWidth(), getMeasuredHeight(), mDebugOutline);

            // Draw the rects
            int numRects = mRects.size();
            for (int i = 0; i < numRects; i++) {
                Pair<Rect, Integer> r = mRects.get(i);
                mTmpPaint.setColor(r.second);
                canvas.drawRect(r.first, mTmpPaint);
            }
        }
    }
}
