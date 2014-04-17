/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.animation.LayoutTransition;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.android.systemui.R;

/**
 *
 */
class QuickSettingsContainerView extends FrameLayout {

    private static boolean sShowScrim = true;

    private final Context mContext;

    // The number of columns in the QuickSettings grid
    private int mNumColumns;

    private boolean mKeyguardShowing;
    private int mMaxRows;
    private int mMaxRowsOnKeyguard;

    // The gap between tiles in the QuickSettings grid
    private float mCellGap;

    private ScrimView mScrim;

    public QuickSettingsContainerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        updateResources();
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mScrim = new ScrimView(mContext);
        addView(mScrim);
        mScrim.setAlpha(sShowScrim ? 1 : 0);
        // TODO: Setup the layout transitions
        LayoutTransition transitions = getLayoutTransition();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mScrim.getAlpha() == 1) {
            mScrim.animate().alpha(0).setDuration(1000).start();
            sShowScrim = false;
        }
        return super.onTouchEvent(event);
    }

    void updateResources() {
        Resources r = getContext().getResources();
        mCellGap = r.getDimension(R.dimen.quick_settings_cell_gap);
        mNumColumns = r.getInteger(R.integer.quick_settings_num_columns);
        mMaxRows = r.getInteger(R.integer.quick_settings_max_rows);
        mMaxRowsOnKeyguard = r.getInteger(R.integer.quick_settings_max_rows_keyguard);
        requestLayout();
    }

    void setKeyguardShowing(boolean showing) {
        mKeyguardShowing = showing;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Calculate the cell width dynamically
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        int availableWidth = (int) (width - getPaddingLeft() - getPaddingRight() -
                (mNumColumns - 1) * mCellGap);
        float cellWidth = (float) Math.ceil(((float) availableWidth) / mNumColumns);

        // Update each of the children's widths accordingly to the cell width
        final int N = getChildCount();
        int cellHeight = 0;
        int cursor = 0;
        int maxRows = mKeyguardShowing ? mMaxRowsOnKeyguard : mMaxRows;

        for (int i = 0; i < N; ++i) {
            if (getChildAt(i).equals(mScrim)) {
                continue;
            }
            // Update the child's width
            QuickSettingsTileView v = (QuickSettingsTileView) getChildAt(i);
            if (v.getVisibility() != View.GONE) {
                int row = (int) (cursor / mNumColumns);
                if (row >= maxRows) continue;

                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
                int colSpan = v.getColumnSpan();
                lp.width = (int) ((colSpan * cellWidth) + (colSpan - 1) * mCellGap);

                // Measure the child
                int newWidthSpec = MeasureSpec.makeMeasureSpec(lp.width, MeasureSpec.EXACTLY);
                int newHeightSpec = MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY);
                v.measure(newWidthSpec, newHeightSpec);

                // Save the cell height
                if (cellHeight <= 0) {
                    cellHeight = v.getMeasuredHeight();
                }
                cursor += colSpan;
            }
        }

        // Set the measured dimensions.  We always fill the tray width, but wrap to the height of
        // all the tiles.
        int numRows = (int) Math.ceil((float) cursor / mNumColumns);
        int newHeight = (int) ((numRows * cellHeight) + ((numRows - 1) * mCellGap)) +
                getPaddingTop() + getPaddingBottom();
        setMeasuredDimension(width, newHeight);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        mScrim.bringToFront();
        final int N = getChildCount();
        final boolean isLayoutRtl = isLayoutRtl();
        final int width = getWidth();

        int x = getPaddingStart();
        int y = getPaddingTop();
        int cursor = 0;
        int maxRows = mKeyguardShowing ? mMaxRowsOnKeyguard : mMaxRows;

        for (int i = 0; i < N; ++i) {
            if (getChildAt(i).equals(mScrim)) {
                int w = right - left - getPaddingLeft() - getPaddingRight();
                int h = bottom - top - getPaddingTop() - getPaddingBottom();
                mScrim.measure(
                        MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
                mScrim.layout(getPaddingLeft(), getPaddingTop(), right, bottom);
                continue;
            }
            QuickSettingsTileView child = (QuickSettingsTileView) getChildAt(i);
            ViewGroup.LayoutParams lp = child.getLayoutParams();
            if (child.getVisibility() != GONE) {
                final int col = cursor % mNumColumns;
                final int colSpan = child.getColumnSpan();

                final int childWidth = lp.width;
                final int childHeight = lp.height;

                int row = (int) (cursor / mNumColumns);
                if (row >= maxRows) continue;

                // Push the item to the next row if it can't fit on this one
                if ((col + colSpan) > mNumColumns) {
                    x = getPaddingStart();
                    y += childHeight + mCellGap;
                    row++;
                }

                final int childLeft = (isLayoutRtl) ? width - x - childWidth : x;
                final int childRight = childLeft + childWidth;

                final int childTop = y;
                final int childBottom = childTop + childHeight;

                // Layout the container
                child.layout(childLeft, childTop, childRight, childBottom);

                // Offset the position by the cell gap or reset the position and cursor when we
                // reach the end of the row
                cursor += child.getColumnSpan();
                if (cursor < (((row + 1) * mNumColumns))) {
                    x += childWidth + mCellGap;
                } else {
                    x = getPaddingStart();
                    y += childHeight + mCellGap;
                }
            }
        }
    }

    private static final class ScrimView extends View {
        private static final int COLOR = 0xaf4285f4;

        private final Paint mLinePaint;
        private final int mStrokeWidth;
        private final Rect mTmp = new Rect();
        private final Paint mTextPaint;
        private final int mTextSize;

        public ScrimView(Context context) {
            super(context);
            setFocusable(false);
            final Resources res = context.getResources();
            mStrokeWidth = res.getDimensionPixelSize(R.dimen.quick_settings_tmp_scrim_stroke_width);
            mTextSize = res.getDimensionPixelSize(R.dimen.quick_settings_tmp_scrim_text_size);

            mLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mLinePaint.setColor(COLOR);
            mLinePaint.setStrokeWidth(mStrokeWidth);
            mLinePaint.setStrokeJoin(Paint.Join.ROUND);
            mLinePaint.setStrokeCap(Paint.Cap.ROUND);
            mLinePaint.setStyle(Paint.Style.STROKE);

            mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            mTextPaint.setColor(COLOR);
            mTextPaint.setTextSize(mTextSize);
            mTextPaint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final int w = getMeasuredWidth();
            final int h = getMeasuredHeight();
            final int f = mStrokeWidth * 3 / 4;

            canvas.drawPath(line(f, h / 2, w - f, h / 2), mLinePaint);
            canvas.drawPath(line(w / 2, f, w / 2, h - f), mLinePaint);

            final int s = mStrokeWidth;
            mTextPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText("FUTURE", w / 2 - s, h / 2 - s, mTextPaint);
            mTextPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText("SITE OF", w / 2 + s, h / 2 - s , mTextPaint);
            mTextPaint.setTextAlign(Paint.Align.RIGHT);
            drawUnder(canvas, "QUANTUM", w / 2 - s, h / 2 + s);
            mTextPaint.setTextAlign(Paint.Align.LEFT);
            drawUnder(canvas, "SETTINGS", w / 2 + s, h / 2 + s);
        }

        private void drawUnder(Canvas c, String text, float x, float y) {
            if (mTmp.isEmpty()) {
                mTextPaint.getTextBounds(text, 0, text.length(), mTmp);
            }
            c.drawText(text, x, y + mTmp.height() * .85f, mTextPaint);
        }

        private Path line(float x1, float y1, float x2, float y2) {
            final int a = mStrokeWidth * 2;
            final Path p = new Path();
            p.moveTo(x1, y1);
            p.lineTo(x2, y2);
            if (y1 == y2) {
                p.moveTo(x1 + a, y1 + a);
                p.lineTo(x1, y1);
                p.lineTo(x1 + a, y1 - a);

                p.moveTo(x2 - a, y2 - a);
                p.lineTo(x2, y2);
                p.lineTo(x2 - a, y2 + a);
            }
            if (x1 == x2) {
                p.moveTo(x1 - a, y1 + a);
                p.lineTo(x1, y1);
                p.lineTo(x1 + a, y1 + a);

                p.moveTo(x2 - a, y2 - a);
                p.lineTo(x2, y2);
                p.lineTo(x2 + a, y2 - a);
            }
            return p;
        }
    }
}