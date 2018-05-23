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
import android.content.res.TypedArray;
import android.graphics.drawable.RippleDrawable;
import android.util.AttributeSet;
import android.util.Pair;
import android.view.Gravity;
import android.view.RemotableViewMethod;
import android.view.View;
import android.view.ViewGroup;
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

    private final int mGravity;
    private int mTotalWidth = 0;
    private ArrayList<Pair<Integer, TextView>> mMeasureOrderTextViews = new ArrayList<>();
    private ArrayList<View> mMeasureOrderOther = new ArrayList<>();
    private boolean mEmphasizedMode;
    private int mDefaultPaddingBottom;
    private int mDefaultPaddingTop;
    private int mEmphasizedHeight;
    private int mRegularHeight;

    public NotificationActionListLayout(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public NotificationActionListLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public NotificationActionListLayout(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        int[] attrIds = { android.R.attr.gravity };
        TypedArray ta = context.obtainStyledAttributes(attrs, attrIds, defStyleAttr, defStyleRes);
        mGravity = ta.getInt(0, 0);
        ta.recycle();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mEmphasizedMode) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        final int N = getChildCount();
        int textViews = 0;
        int otherViews = 0;
        int notGoneChildren = 0;

        for (int i = 0; i < N; i++) {
            View c = getChildAt(i);
            if (c instanceof TextView) {
                textViews++;
            } else {
                otherViews++;
            }
            if (c.getVisibility() != GONE) {
                notGoneChildren++;
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

        if (needRebuild) {
            rebuildMeasureOrder(textViews, otherViews);
        }

        final boolean constrained =
                MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED;

        final int innerWidth = MeasureSpec.getSize(widthMeasureSpec) - mPaddingLeft - mPaddingRight;
        final int otherSize = mMeasureOrderOther.size();
        int usedWidth = 0;

        int measuredChildren = 0;
        for (int i = 0; i < N; i++) {
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
        // For some reason ripples + notification actions seem to be an unhappy combination
        // b/69474443 so just turn them off for now.
        if (child.getBackground() instanceof RippleDrawable) {
            ((RippleDrawable)child.getBackground()).setForceSoftware(true);
        }
    }

    @Override
    public void onViewRemoved(View child) {
        super.onViewRemoved(child);
        clearMeasureOrder();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (mEmphasizedMode) {
            super.onLayout(changed, left, top, right, bottom);
            return;
        }
        final boolean isLayoutRtl = isLayoutRtl();
        final int paddingTop = mPaddingTop;
        final boolean centerAligned = (mGravity & Gravity.CENTER_HORIZONTAL) != 0;

        int childTop;
        int childLeft;
        if (centerAligned) {
            childLeft = mPaddingLeft + left + (right - left) / 2 - mTotalWidth / 2;
        } else {
            childLeft = mPaddingLeft;
            int absoluteGravity = Gravity.getAbsoluteGravity(Gravity.START, getLayoutDirection());
            if (absoluteGravity == Gravity.RIGHT) {
                childLeft += right - left - mTotalWidth;
            }
        }


        // Where bottom of child should go
        final int height = bottom - top;

        // Space available for child
        int innerHeight = height - paddingTop - mPaddingBottom;

        final int count = getChildCount();

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
        mDefaultPaddingBottom = getPaddingBottom();
        mDefaultPaddingTop = getPaddingTop();
        updateHeights();
    }

    private void updateHeights() {
        int paddingTop = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_margin);
        // same padding on bottom and at end
        int paddingBottom = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_content_margin_end);
        mEmphasizedHeight = paddingBottom + paddingTop + getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_action_emphasized_height);
        mRegularHeight = getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.notification_action_list_height);
    }

    /**
     * Set whether the list is in a mode where some actions are emphasized. This will trigger an
     * equal measuring where all actions are full height and change a few parameters like
     * the padding.
     */
    @RemotableViewMethod
    public void setEmphasizedMode(boolean emphasizedMode) {
        mEmphasizedMode = emphasizedMode;
        int height;
        if (emphasizedMode) {
            int paddingTop = getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.notification_content_margin);
            // same padding on bottom and at end
            int paddingBottom = getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.notification_content_margin_end);
            height = mEmphasizedHeight;
            int buttonPaddingInternal = getResources().getDimensionPixelSize(
                    com.android.internal.R.dimen.button_inset_vertical_material);
            setPaddingRelative(getPaddingStart(),
                    paddingTop - buttonPaddingInternal,
                    getPaddingEnd(),
                    paddingBottom - buttonPaddingInternal);
        } else {
            setPaddingRelative(getPaddingStart(),
                    mDefaultPaddingTop,
                    getPaddingEnd(),
                    mDefaultPaddingBottom);
            height = mRegularHeight;
        }
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        layoutParams.height = height;
        setLayoutParams(layoutParams);
    }

    public int getExtraMeasureHeight() {
        if (mEmphasizedMode) {
            return mEmphasizedHeight - mRegularHeight;
        }
        return 0;
    }

    public static final Comparator<Pair<Integer, TextView>> MEASURE_ORDER_COMPARATOR
            = (a, b) -> a.first.compareTo(b.first);
}
