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
import android.annotation.DrawableRes;
import android.annotation.StringRes;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.IndentingPrintWriter;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.android.settingslib.Utils;
import com.android.systemui.R;
import com.android.systemui.statusbar.notification.stack.ExpandableViewState;
import com.android.systemui.statusbar.notification.stack.ViewState;
import com.android.systemui.util.DumpUtilsKt;

import java.io.PrintWriter;

public class FooterView extends StackScrollerDecorView {
    private FooterViewButton mClearAllButton;
    private FooterViewButton mManageButton;
    private boolean mShowHistory;
    // String cache, for performance reasons.
    // Reading them from a Resources object can be quite slow sometimes.
    private String mManageNotificationText;
    private String mManageNotificationHistoryText;

    // Footer label
    private TextView mSeenNotifsFooterTextView;
    private @StringRes int mSeenNotifsFilteredText;
    private int mUnlockIconSize;

    public FooterView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected View findContentView() {
        return findViewById(R.id.content);
    }

    protected View findSecondaryView() {
        return findViewById(R.id.dismiss_text);
    }

    @Override
    public void dump(PrintWriter pwOriginal, String[] args) {
        IndentingPrintWriter pw = DumpUtilsKt.asIndenting(pwOriginal);
        super.dump(pw, args);
        DumpUtilsKt.withIncreasedIndent(pw, () -> {
            pw.println("visibility: " + DumpUtilsKt.visibilityString(getVisibility()));
            pw.println("manageButton showHistory: " + mShowHistory);
            pw.println("manageButton visibility: "
                    + DumpUtilsKt.visibilityString(mClearAllButton.getVisibility()));
            pw.println("dismissButton visibility: "
                    + DumpUtilsKt.visibilityString(mClearAllButton.getVisibility()));
        });
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mClearAllButton = (FooterViewButton) findSecondaryView();
        mManageButton = findViewById(R.id.manage_text);
        mSeenNotifsFooterTextView = findViewById(R.id.unlock_prompt_footer);
        updateResources();
        updateText();
        updateColors();
    }

    public void setFooterLabelTextAndIcon(@StringRes int text, @DrawableRes int icon) {
        mSeenNotifsFilteredText = text;
        if (mSeenNotifsFilteredText != 0) {
            mSeenNotifsFooterTextView.setText(mSeenNotifsFilteredText);
        } else {
            mSeenNotifsFooterTextView.setText(null);
        }
        Drawable drawable;
        if (icon == 0) {
            drawable = null;
        } else {
            drawable = getResources().getDrawable(icon);
            drawable.setBounds(0, 0, mUnlockIconSize, mUnlockIconSize);
        }
        mSeenNotifsFooterTextView.setCompoundDrawablesRelative(drawable, null, null, null);
        updateFooterVisibilityMode();
    }

    private void updateFooterVisibilityMode() {
        if (mSeenNotifsFilteredText != 0) {
            mManageButton.setVisibility(View.GONE);
            mClearAllButton.setVisibility(View.GONE);
            mSeenNotifsFooterTextView.setVisibility(View.VISIBLE);
        } else {
            mManageButton.setVisibility(View.VISIBLE);
            mClearAllButton.setVisibility(View.VISIBLE);
            mSeenNotifsFooterTextView.setVisibility(View.GONE);
        }
    }

    public void setManageButtonClickListener(OnClickListener listener) {
        mManageButton.setOnClickListener(listener);
    }

    public void setClearAllButtonClickListener(OnClickListener listener) {
        mClearAllButton.setOnClickListener(listener);
    }

    public boolean isOnEmptySpace(float touchX, float touchY) {
        return touchX < mContent.getX()
                || touchX > mContent.getX() + mContent.getWidth()
                || touchY < mContent.getY()
                || touchY > mContent.getY() + mContent.getHeight();
    }

    public void showHistory(boolean showHistory) {
        if (mShowHistory == showHistory) {
            return;
        }
        mShowHistory = showHistory;
        updateText();
    }

    private void updateText() {
        if (mShowHistory) {
            mManageButton.setText(mManageNotificationHistoryText);
            mManageButton.setContentDescription(mManageNotificationHistoryText);
        } else {
            mManageButton.setText(mManageNotificationText);
            mManageButton.setContentDescription(mManageNotificationText);
        }
    }

    public boolean isHistoryShown() {
        return mShowHistory;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateColors();
        mClearAllButton.setText(R.string.clear_all_notifications_text);
        mClearAllButton.setContentDescription(
                mContext.getString(R.string.accessibility_clear_all));
        updateResources();
        updateText();
    }

    /**
     * Update the text and background colors for the current color palette and night mode setting.
     */
    public void updateColors() {
        Resources.Theme theme = mContext.getTheme();
        int textColor = getResources().getColor(R.color.notif_pill_text, theme);
        mClearAllButton.setBackground(theme.getDrawable(R.drawable.notif_footer_btn_background));
        mClearAllButton.setTextColor(textColor);
        mManageButton.setBackground(theme.getDrawable(R.drawable.notif_footer_btn_background));
        mManageButton.setTextColor(textColor);
        final @ColorInt int labelTextColor =
                Utils.getColorAttrDefaultColor(mContext, android.R.attr.textColorPrimary);
        mSeenNotifsFooterTextView.setTextColor(labelTextColor);
        mSeenNotifsFooterTextView.setCompoundDrawableTintList(
                ColorStateList.valueOf(labelTextColor));
    }

    private void updateResources() {
        mManageNotificationText = getContext().getString(R.string.manage_notifications_text);
        mManageNotificationHistoryText = getContext()
                .getString(R.string.manage_notifications_history_text);
        mUnlockIconSize = getResources()
                .getDimensionPixelSize(R.dimen.notifications_unseen_footer_icon_size);
    }

    @Override
    @NonNull
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
