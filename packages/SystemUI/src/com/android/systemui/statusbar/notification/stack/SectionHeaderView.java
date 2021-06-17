/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.stack;

import android.annotation.ColorInt;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.content.Context;
import android.content.res.ColorStateList;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.android.systemui.R;
import com.android.systemui.statusbar.notification.row.StackScrollerDecorView;

/**
 * Header displayed above a notification section in the shade. Currently used for Alerting and
 * Silent sections.
 */
public class SectionHeaderView extends StackScrollerDecorView {

    private ViewGroup mContents;
    private TextView mLabelView;
    private ImageView mClearAllButton;
    @StringRes @Nullable private Integer mLabelTextId;
    @Nullable private View.OnClickListener mLabelClickListener = null;
    @Nullable private View.OnClickListener mOnClearClickListener = null;

    public SectionHeaderView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        mContents = requireViewById(R.id.content);
        bindContents();
        super.onFinishInflate();
        setVisible(true /* nowVisible */, false /* animate */);
    }

    private void bindContents() {
        mLabelView = requireViewById(R.id.header_label);
        mClearAllButton = requireViewById(R.id.btn_clear_all);
        if (mOnClearClickListener != null) {
            mClearAllButton.setOnClickListener(mOnClearClickListener);
        }
        if (mLabelClickListener != null) {
            mLabelView.setOnClickListener(mLabelClickListener);
        }
        if (mLabelTextId != null) {
            mLabelView.setText(mLabelTextId);
        }
    }

    @Override
    protected View findContentView() {
        return mContents;
    }

    @Override
    protected View findSecondaryView() {
        return null;
    }

    @Override
    public boolean isTransparent() {
        return true;
    }

    void setAreThereDismissableGentleNotifs(boolean areThereDismissableGentleNotifs) {
        mClearAllButton.setVisibility(areThereDismissableGentleNotifs ? View.VISIBLE : View.GONE);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return super.onInterceptTouchEvent(ev);
    }

    /**
     * Fired whenever the user clicks on the body of the header (e.g. no sub-buttons or anything).
     */
    public void setOnHeaderClickListener(View.OnClickListener listener) {
        mLabelClickListener = listener;
        mLabelView.setOnClickListener(listener);
    }

    @Override
    protected void applyContentTransformation(float contentAlpha, float translationY) {
        super.applyContentTransformation(contentAlpha, translationY);
        mLabelView.setAlpha(contentAlpha);
        mLabelView.setTranslationY(translationY);
        mClearAllButton.setAlpha(contentAlpha);
        mClearAllButton.setTranslationY(translationY);
    }

    /** Fired when the user clicks on the "X" button on the far right of the header. */
    public void setOnClearAllClickListener(View.OnClickListener listener) {
        mOnClearClickListener = listener;
        mClearAllButton.setOnClickListener(listener);
    }

    @Override
    public boolean needsClippingToShelf() {
        return true;
    }

    /** Sets text to be displayed in the header */
    public void setHeaderText(@StringRes int resId) {
        mLabelTextId = resId;
        mLabelView.setText(resId);
    }

    void setForegroundColor(@ColorInt int color) {
        mLabelView.setTextColor(color);
        mClearAllButton.setImageTintList(ColorStateList.valueOf(color));
    }
}
