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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.row;

import android.annotation.ColorInt;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.view.View;

import com.android.systemui.R;
import com.android.systemui.statusbar.notification.stack.ExpandableViewState;
import com.android.systemui.statusbar.notification.stack.ViewState;

public class FooterView extends StackScrollerDecorView {
    private final int mClearAllTopPadding;
    private FooterViewButton mDismissButton;
    private FooterViewButton mManageButton;
    private boolean mShowHistory;

    public FooterView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mClearAllTopPadding = context.getResources().getDimensionPixelSize(
                R.dimen.clear_all_padding_top);
    }

    @Override
    protected View findContentView() {
        return findViewById(R.id.content);
    }

    protected View findSecondaryView() {
        return findViewById(R.id.dismiss_text);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mDismissButton = (FooterViewButton) findSecondaryView();
        mManageButton = findViewById(R.id.manage_text);
    }

    public void setTextColor(@ColorInt int color) {
        mManageButton.setTextColor(color);
        mDismissButton.setTextColor(color);
    }

    public void setManageButtonClickListener(OnClickListener listener) {
        mManageButton.setOnClickListener(listener);
    }

    public void setDismissButtonClickListener(OnClickListener listener) {
        mDismissButton.setOnClickListener(listener);
    }

    public boolean isOnEmptySpace(float touchX, float touchY) {
        return touchX < mContent.getX()
                || touchX > mContent.getX() + mContent.getWidth()
                || touchY < mContent.getY()
                || touchY > mContent.getY() + mContent.getHeight();
    }

    public void showHistory(boolean showHistory) {
        mShowHistory = showHistory;
        if (mShowHistory) {
            mManageButton.setText(R.string.manage_notifications_history_text);
            mManageButton.setContentDescription(
                    mContext.getString(R.string.manage_notifications_history_text));
        } else {
            mManageButton.setText(R.string.manage_notifications_text);
            mManageButton.setContentDescription(
                    mContext.getString(R.string.manage_notifications_text));
        }
    }

    public boolean isHistoryShown() {
        return mShowHistory;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int textColor = getResources().getColor(R.color.notif_pill_text);
        Resources.Theme theme = getContext().getTheme();
        mDismissButton.setBackground(
                getResources().getDrawable(R.drawable.notif_footer_btn_background, theme));
        mDismissButton.setTextColor(textColor);
        mManageButton.setBackground(
                getResources().getDrawable(R.drawable.notif_footer_btn_background, theme));
        mManageButton = findViewById(R.id.manage_text);
        mDismissButton.setText(R.string.clear_all_notifications_text);
        mManageButton.setTextColor(textColor);
        mDismissButton.setContentDescription(
                mContext.getString(R.string.accessibility_clear_all));
        showHistory(mShowHistory);
    }

    @Override
    public ExpandableViewState createExpandableViewState() {
        return new FooterViewState();
    }

    public class FooterViewState extends ExpandableViewState {
        /**
         * used to hide the content of the footer to animate.
         * #hide is applied without animation, but #hideContent has animation.
         */
        public boolean hideContent;

        @Override
        public void copyFrom(ViewState viewState) {
            super.copyFrom(viewState);
            if (viewState instanceof FooterViewState) {
                hideContent = ((FooterViewState) viewState).hideContent;
            }
        }

        @Override
        public void applyToView(View view) {
            super.applyToView(view);
            if (view instanceof FooterView) {
                FooterView footerView = (FooterView) view;
                footerView.setContentVisible(!hideContent);
            }
        }
    }
}
