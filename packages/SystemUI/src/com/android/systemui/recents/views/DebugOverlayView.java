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
import android.util.AttributeSet;
import android.util.Pair;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.SeekBar;
import com.android.systemui.R;
import com.android.systemui.recents.RecentsConfiguration;

import java.util.ArrayList;

/**
 * A full screen overlay layer that allows us to draw views from throughout the system on the top
 * most layer.
 */
public class DebugOverlayView extends FrameLayout implements SeekBar.OnSeekBarChangeListener {

    public interface DebugOverlayViewCallbacks {
        public void onPrimarySeekBarChanged(float progress);
        public void onSecondarySeekBarChanged(float progress);
    }

    final static int sCornerRectSize = 50;

    RecentsConfiguration mConfig;
    DebugOverlayViewCallbacks mCb;

    ArrayList<Pair<Rect, Integer>> mRects = new ArrayList<Pair<Rect, Integer>>();
    String mText;
    Paint mDebugOutline = new Paint();
    Paint mTmpPaint = new Paint();
    Rect mTmpRect = new Rect();
    boolean mEnabled = true;

    SeekBar mPrimarySeekBar;
    SeekBar mSecondarySeekBar;

    public DebugOverlayView(Context context) {
        this(context, null);
    }

    public DebugOverlayView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public DebugOverlayView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public DebugOverlayView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mConfig = RecentsConfiguration.getInstance();
        mDebugOutline.setColor(0xFFff0000);
        mDebugOutline.setStyle(Paint.Style.STROKE);
        mDebugOutline.setStrokeWidth(8f);
        setWillNotDraw(false);
    }

    public void setCallbacks(DebugOverlayViewCallbacks cb) {
        mCb = cb;
    }

    @Override
    protected void onFinishInflate() {
        mPrimarySeekBar = (SeekBar) findViewById(R.id.debug_seek_bar_1);
        mPrimarySeekBar.setOnSeekBarChangeListener(this);
        mSecondarySeekBar = (SeekBar) findViewById(R.id.debug_seek_bar_2);
        mSecondarySeekBar.setOnSeekBarChangeListener(this);
    }

    /** Enables the debug overlay drawing. */
    public void enable() {
        mEnabled = true;
        setVisibility(View.VISIBLE);
    }

    /** Disables the debug overlay drawing. */
    public void disable() {
        mEnabled = false;
        setVisibility(View.GONE);
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

    /** Sets the debug text at the bottom of the screen. */
    void setText(String message) {
        mText = message;
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

            // Draw the text
            if (mText != null && mText.length() > 0) {
                mTmpPaint.setColor(0xFFff0000);
                mTmpPaint.setTextSize(60);
                mTmpPaint.getTextBounds(mText, 0, 1, mTmpRect);
                canvas.drawText(mText, 10f, getMeasuredHeight() - mTmpRect.height() - mConfig.systemInsets.bottom, mTmpPaint);
            }
        }
    }

    /**** SeekBar.OnSeekBarChangeListener Implementation ****/

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        // Do nothing
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        // Do nothing
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (seekBar == mPrimarySeekBar) {
            mCb.onPrimarySeekBarChanged((float) progress / mPrimarySeekBar.getMax());
        } else if (seekBar == mSecondarySeekBar) {
            mCb.onSecondarySeekBarChanged((float) progress / mSecondarySeekBar.getMax());
        }
    }
}
