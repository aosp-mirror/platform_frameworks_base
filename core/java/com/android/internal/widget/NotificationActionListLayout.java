/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.widget;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.Gravity;
import android.view.RemotableViewMethod;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Comparator;

/**
 * Layout for notification actions that ensures that no action consumes more than their share of
 * the remaining available width, and the last action consumes the remaining space.
 */
@RemoteViews.RemoteView
public class NotificationActionListLayout extends LinearLayout {

    private int mTotalWidth = 0;
    private ArrayList<Pair<Integer, TextView>> mMeasureOrderTextViews = new ArrayList<>();
    private ArrayList<View> mMeasureOrderOther = new ArrayList<>();
    private boolean mMeasureLinearly;
    private int mDefaultPaddingEnd;
    private Drawable mDefaultBackground;

    public NotificationActionListLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mMeasureLinearly) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        final int N = getChildCount();
        int textViews = 0;
        int otherViews = 0;
        int notGoneChildren = 0;

        View lastNotGoneChild = null;
        for (int i = 0; i < N; i++) {
            View c = getChildAt(i);
            if (c instanceof TextView) {
                textViews++;
            } else {
                otherViews++;
            }
            if (c.getVisibility() != GONE) {
                notGoneChildren++;
                lastNotGoneChild = c;
            }
        }

        // Rebuild the measure order if the number of children changed or the text length of
        // any of the children changed.
        boolean needRebuild = false;
        if (textViews != mMeasureOrderTextViews.size()
                || otherViews != mMeasureOrderOther.size()) {
            needRebuild = true;
        }
        if (!needRebuild) {
            final int size = mMeasureOrderTextViews.size();
            for (int i = 0; i < size; i++) {
                Pair<Integer, TextView> pair = mMeasureOrderTextViews.get(i);
                if (pair.first != pair.second.getText().length()) {
                    needRebuild = true;
                }
            }
        }
        if (notGoneChildren > 1 && needRebuild) {
            rebuildMeasureOrder(textViews, otherViews);
        }

        final boolean constrained =
                MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED;

        final int innerWidth = MeasureSpec.getSize(widthMeasureSpec) - mPaddingLeft - mPaddingRight;
        final int otherSize = mMeasureOrderOther.size();
        int usedWidth = 0;

        // Optimization: Don't do this if there's only one child.
        int measuredChildren = 0;
        for (int i = 0; i < N && notGoneChildren > 1; i++) {
            // Measure shortest children first. To avoid measuring twice, we approximate by looking
            // at the text length.
            View c;
            if (i < otherSize) {
                c = mMeasureOrderOther.get(i);
            } else {
                c = mMeasureOrderTextViews.get(i - otherSize).second;
            }
            if (c.getVisibility() == GONE) {
                continue;
            }
            MarginLayoutParams lp = (MarginLayoutParams) c.getLayoutParams();

            int usedWidthForChild = usedWidth;
            if (constrained) {
                // Make sure that this child doesn't consume more than its share of the remaining
                // total available space. Not used space will benefit subsequent views. Since we
                // measure in the order of (approx.) size, a large view can still take more than its
                // share if the others are small.
                int availableWidth = innerWidth - usedWidth;
                int maxWidthForChild = availableWidth / (notGoneChildren - measuredChildren);

                usedWidthForChild = innerWidth - maxWidthForChild;
            }

            measureChildWithMargins(c, widthMeasureSpec, usedWidthForChild,
                    heightMeasureSpec, 0 /* usedHeight */);

            usedWidth += c.getMeasuredWidth() + lp.rightMargin + lp.leftMargin;
            measuredChildren++;
        }

        // Make sure to measure the last child full-width if we didn't use up the entire width,
        // or we didn't measure yet because there's just one child.
        if (lastNotGoneChild != null && (constrained && usedWidth < innerWidth
                || notGoneChildren == 1)) {
            MarginLayoutParams lp = (MarginLayoutParams) lastNotGoneChild.getLayoutParams();
            if (notGoneChildren > 1) {
                // Need to make room, since we already measured this once.
                usedWidth -= lastNotGoneChild.getMeasuredWidth() + lp.rightMargin + lp.leftMargin;
            }

            int originalWidth = lp.width;
            lp.width = LayoutParams.MATCH_PARENT;
            measureChildWithMargins(lastNotGoneChild, widthMeasureSpec, usedWidth,
                    heightMeasureSpec, 0 /* usedHeight */);
            lp.width = originalWidth;

            usedWidth += lastNotGoneChild.getMeasuredWidth() + lp.rightMargin + lp.leftMargin;
        }

        mTotalWidth = usedWidth + mPaddingRight + mPaddingLeft;
        setMeasuredDimension(resolveSize(getSuggestedMinimumWidth(), widthMeasureSpec),
                resolveSize(getSuggestedMinimumHeight(), heightMeasureSpec));
    }

    private void rebuildMeasureOrder(int capacityText, int capacityOther) {
        clearMeasureOrder();
        mMeasureOrderTextViews.ensureCapacity(capacityText);
        mMeasureOrderOther.ensureCapacity(capacityOther);
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            View c = getChildAt(i);
            if (c instanceof TextView && ((TextView) c).getText().length() > 0) {
                mMeasureOrderTextViews.add(Pair.create(((TextView) c).getText().length(),
                        (TextView)c));
            } else {
                mMeasureOrderOther.add(c);
            }
        }
        mMeasureOrderTextViews.sort(MEASURE_ORDER_COMPARATOR);
    }

    private void clearMeasureOrder() {
        mMeasureOrderOther.clear();
        mMeasureOrderTextViews.clear();
    }

    @Override
    public void onViewAdded(View child) {
        super.onViewAdded(child);
        clearMeasureOrder();
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        clearMeasureOrder();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (mMeasureLinearly) {
            super.onLayout(changed, left, top, right, bottom);
            return;
        }
        final boolean isLayoutRtl = isLayoutRtl();
        final int paddingTop = mPaddingTop;

        int childTop;
        int childLeft;

        // Where bottom of child should go
        final int height = bottom - top;

        // Space available for child
        int innerHeight = height - paddingTop - mPaddingBottom;

        final int count = getChildCount();

        final int layoutDirection = getLayoutDirection();
        switch (Gravity.getAbsoluteGravity(Gravity.START, layoutDirection)) {
            case Gravity.RIGHT:
                // mTotalWidth contains the padding already
                childLeft = mPaddingLeft + right - left - mTotalWidth;
                break;

            case Gravity.LEFT:
            default:
                childLeft = mPaddingLeft;
                break;
        }

        int start = 0;
        int dir = 1;
        //In case of RTL, start drawing from the last child.
        if (isLayoutRtl) {
            start = count - 1;
            dir = -1;
        }

        for (int i = 0; i < count; i++) {
            final int childIndex = start + dir * i;
            final View child = getChildAt(childIndex);
            if (child.getVisibility() != GONE) {
                final int childWidth = child.getMeasuredWidth();
                final int childHeight = child.getMeasuredHeight();

                final MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();

                childTop = paddingTop + ((innerHeight - childHeight) / 2)
                            + lp.topMargin - lp.bottomMargin;

                childLeft += lp.leftMargin;
                child.layout(childLeft, childTop, childLeft + childWidth, childTop + childHeight);
                childLeft += childWidth + lp.rightMargin;
            }
        }
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mDefaultPaddingEnd = getPaddingEnd();
        mDefaultBackground = getBackground();
    }

    /**
     * Set whether the list is in a mode where some actions are emphasized. This will trigger an
     * equal measuring where all actions are full height and change a few parameters like
     * the padding.
     */
    @RemotableViewMethod
    public void setEmphasizedMode(boolean emphasizedMode) {
        mMeasureLinearly = emphasizedMode;
        setPaddingRelative(getPaddingStart(), getPaddingTop(),
                emphasizedMode ? 0 : mDefaultPaddingEnd, getPaddingBottom());
        setBackground(emphasizedMode ? null : mDefaultBackground);
        requestLayout();
    }

    public static final Comparator<Pair<Integer, TextView>> MEASURE_ORDER_COMPARATOR
            = (a, b) -> a.first.compareTo(b.first);
}
