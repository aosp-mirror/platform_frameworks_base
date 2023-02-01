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

package com.android.systemui.statusbar.notification.row;

import static android.app.Notification.COLOR_INVALID;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.TypedArray;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.ColorInt;

import com.android.keyguard.AlphaOptimizedLinearLayout;
import com.android.systemui.R;
import com.android.systemui.statusbar.CrossFadeHelper;
import com.android.systemui.statusbar.TransformableView;
import com.android.systemui.statusbar.ViewTransformationHelper;
import com.android.systemui.statusbar.notification.NotificationFadeAware;
import com.android.systemui.statusbar.notification.TransformState;

/**
 * A hybrid view which may contain information about one ore more notifications.
 */
public class HybridNotificationView extends AlphaOptimizedLinearLayout
        implements TransformableView, NotificationFadeAware {

    protected final ViewTransformationHelper mTransformationHelper = new ViewTransformationHelper();
    protected TextView mTitleView;
    protected TextView mTextView;
    protected int mPrimaryTextColor = COLOR_INVALID;
    protected int mSecondaryTextColor = COLOR_INVALID;

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
    }

    public TextView getTitleView() {
        return mTitleView;
    }

    public TextView getTextView() {
        return mTextView;
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        resolveThemeTextColors();
        mTitleView = findViewById(R.id.notification_title);
        mTextView = findViewById(R.id.notification_text);
        applyTextColor(mTitleView, mPrimaryTextColor);
        applyTextColor(mTextView, mSecondaryTextColor);
        mTransformationHelper.setCustomTransformation(
                new FadeOutAndDownWithTitleTransformation(mTextView),
                TRANSFORMING_VIEW_TEXT);
        mTransformationHelper.addTransformedView(TRANSFORMING_VIEW_TITLE, mTitleView);
        mTransformationHelper.addTransformedView(TRANSFORMING_VIEW_TEXT, mTextView);
    }

    protected void applyTextColor(TextView textView, @ColorInt int textColor) {
        if (textColor != COLOR_INVALID) {
            textView.setTextColor(textColor);
        }
    }

    private void resolveThemeTextColors() {
        try (TypedArray ta = mContext.getTheme().obtainStyledAttributes(
                android.R.style.Theme_DeviceDefault_DayNight, new int[]{
                        android.R.attr.textColorPrimary,
                        android.R.attr.textColorSecondary
                })) {
            if (ta != null) {
                mPrimaryTextColor = ta.getColor(0, mPrimaryTextColor);
                mSecondaryTextColor = ta.getColor(1, mSecondaryTextColor);
            }
        }
    }

    public void bind(@Nullable CharSequence title, @Nullable CharSequence text,
            @Nullable View contentView) {
        mTitleView.setText(title);
        mTitleView.setVisibility(TextUtils.isEmpty(title) ? GONE : VISIBLE);
        if (TextUtils.isEmpty(text)) {
            mTextView.setVisibility(GONE);
            mTextView.setText(null);
        } else {
            mTextView.setVisibility(VISIBLE);
            mTextView.setText(text.toString());
        }
        requestLayout();
    }

    @Override
    public TransformState getCurrentState(int fadingView) {
        return mTransformationHelper.getCurrentState(fadingView);
    }

    @Override
    public void transformTo(TransformableView notification, Runnable endRunnable) {
        mTransformationHelper.transformTo(notification, endRunnable);
    }

    @Override
    public void transformTo(TransformableView notification, float transformationAmount) {
        mTransformationHelper.transformTo(notification, transformationAmount);
    }

    @Override
    public void transformFrom(TransformableView notification) {
        mTransformationHelper.transformFrom(notification);
    }

    @Override
    public void transformFrom(TransformableView notification, float transformationAmount) {
        mTransformationHelper.transformFrom(notification, transformationAmount);
    }

    @Override
    public void setVisible(boolean visible) {
        setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        mTransformationHelper.setVisible(visible);
    }

    @Override
    public void setNotificationFaded(boolean faded) {}

    protected static class FadeOutAndDownWithTitleTransformation extends
            ViewTransformationHelper.CustomTransformation {

        private final View mView;

        public FadeOutAndDownWithTitleTransformation(View view) {
            mView = view;
        }

        @Override
        public boolean transformTo(TransformState ownState, TransformableView notification,
                float transformationAmount) {
            // We want to transform to the same y location as the title
            TransformState otherState = notification.getCurrentState(TRANSFORMING_VIEW_TITLE);
            CrossFadeHelper.fadeOut(mView, transformationAmount);
            if (otherState != null) {
                ownState.transformViewVerticalTo(otherState, transformationAmount);
                otherState.recycle();
            }
            return true;
        }

        @Override
        public boolean transformFrom(TransformState ownState,
                TransformableView notification, float transformationAmount) {
            // We want to transform from the same y location as the title
            TransformState otherState = notification.getCurrentState(TRANSFORMING_VIEW_TITLE);
            CrossFadeHelper.fadeIn(mView, transformationAmount, true /* remap */);
            if (otherState != null) {
                ownState.transformViewVerticalFrom(otherState, transformationAmount);
                otherState.recycle();
            }
            return true;
        }
    }
}
