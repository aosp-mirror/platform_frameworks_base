/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.systemui.statusbar.notification;

import android.annotation.Nullable;
import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.AlphaOptimizedFrameLayout;

/**
 * A hybrid view which may contain information about one ore more notifications.
 */
public class HybridNotificationView extends AlphaOptimizedFrameLayout {

    protected final int mSingleLineHeight;
    protected final int mStartMargin;
    protected final int mEndMargin;
    protected TextView mTitleView;
    protected TextView mTextView;

    public HybridNotificationView(Context context) {
        this(context, null);
    }

    public HybridNotificationView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HybridNotificationView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public HybridNotificationView(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mSingleLineHeight = context.getResources().getDimensionPixelSize(
                R.dimen.notification_single_line_height);
        mStartMargin = context.getResources().getDimensionPixelSize(
                R.dimen.notification_content_margin_start);
        mEndMargin = context.getResources().getDimensionPixelSize(
                R.dimen.notification_content_margin_end);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int totalWidth = MeasureSpec.getSize(widthMeasureSpec);
        int remainingWidth = totalWidth - mStartMargin - mEndMargin;
        int newHeightSpec = MeasureSpec.makeMeasureSpec(
                MeasureSpec.getSize(heightMeasureSpec), MeasureSpec.AT_MOST);
        int newWidthSpec = MeasureSpec.makeMeasureSpec(remainingWidth, MeasureSpec.AT_MOST);
        mTitleView.measure(newWidthSpec, newHeightSpec);
        int maxTitleLength = getResources().getDimensionPixelSize(
                R.dimen.notification_maximum_title_length);
        int titleWidth = mTitleView.getMeasuredWidth();
        int heightSpec = MeasureSpec.makeMeasureSpec(mSingleLineHeight, MeasureSpec.AT_MOST);
        boolean hasText = !TextUtils.isEmpty(mTextView.getText());
        if (titleWidth > maxTitleLength && hasText) {
            titleWidth = maxTitleLength;
            int widthSpec = MeasureSpec.makeMeasureSpec(titleWidth, MeasureSpec.EXACTLY);
            mTitleView.measure(widthSpec, heightSpec);
        }
        if (hasText) {
            remainingWidth -= titleWidth;
            int widthSpec = MeasureSpec.makeMeasureSpec(remainingWidth, MeasureSpec.AT_MOST);
            mTextView.measure(widthSpec, newHeightSpec);
        }
        setMeasuredDimension(totalWidth, mSingleLineHeight);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int childLeft = mStartMargin;
        int childRight = childLeft + mTitleView.getMeasuredWidth();
        int childBottom = (mSingleLineHeight + mTitleView.getMeasuredHeight()) / 2;
        int childTop = childBottom - mTitleView.getMeasuredHeight();
        int rtlLeft = transformForRtl(childLeft);
        int rtlRight = transformForRtl(childRight);
        mTitleView.layout(Math.min(rtlLeft, rtlRight), childTop, Math.max(rtlLeft, rtlRight),
                childBottom);
        childLeft = childRight;
        childRight = childLeft + mTextView.getMeasuredWidth();
        childTop = mTitleView.getTop() + mTitleView.getBaseline() - mTextView.getBaseline();
        childBottom = childTop + mTextView.getMeasuredHeight();
        rtlLeft = transformForRtl(childLeft);
        rtlRight = transformForRtl(childRight);
        mTextView.layout(Math.min(rtlLeft, rtlRight), childTop, Math.max(rtlLeft, rtlRight),
                childBottom);
    }

    private int transformForRtl(int left) {
        return isLayoutRtl() ? getWidth() - left : left;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mTitleView = (TextView) findViewById(R.id.notification_title);
        mTextView = (TextView) findViewById(R.id.notification_text);
    }

    public void bind(CharSequence title) {
        bind(title, null);
    }

    public void bind(CharSequence title, CharSequence text) {
        mTitleView.setText(title);
        mTextView.setText(text);
        requestLayout();
    }
}
