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

package com.android.systemui.statusbar.notification.footer.ui.view;

import static android.graphics.PorterDuff.Mode.SRC_ATOP;

import static com.android.systemui.Flags.notificationFooterBackgroundTintOptimization;
import static com.android.systemui.util.ColorUtilKt.hexColorString;

import android.annotation.ColorInt;
import android.annotation.DrawableRes;
import android.annotation.StringRes;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.ColorFilter;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.IndentingPrintWriter;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.android.settingslib.Utils;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.ColorUpdateLogger;
import com.android.systemui.statusbar.notification.footer.shared.FooterViewRefactor;
import com.android.systemui.statusbar.notification.footer.shared.NotifRedesignFooter;
import com.android.systemui.statusbar.notification.row.FooterViewButton;
import com.android.systemui.statusbar.notification.row.StackScrollerDecorView;
import com.android.systemui.statusbar.notification.stack.AnimationProperties;
import com.android.systemui.statusbar.notification.stack.ExpandableViewState;
import com.android.systemui.statusbar.notification.stack.ViewState;
import com.android.systemui.util.DrawableDumpKt;
import com.android.systemui.util.DumpUtilsKt;

import java.io.PrintWriter;
import java.util.function.Consumer;

public class FooterView extends StackScrollerDecorView {
    private static final String TAG = "FooterView";

    private FooterViewButton mClearAllButton;
    private FooterViewButton mManageOrHistoryButton;
    // The settings & history buttons replace the single manage/history button in the redesign
    private FooterViewButton mSettingsButton;
    private FooterViewButton mHistoryButton;
    private boolean mShouldBeHidden;
    private boolean mShowHistory;
    // String cache, for performance reasons.
    // Reading them from a Resources object can be quite slow sometimes.
    private String mManageNotificationText;
    private String mManageNotificationHistoryText;

    // Footer label
    private TextView mSeenNotifsFooterTextView;
    private String mSeenNotifsFilteredText;
    private Drawable mSeenNotifsFilteredIcon;

    private @StringRes int mClearAllButtonTextId;
    private @StringRes int mClearAllButtonDescriptionId;
    private @StringRes int mManageOrHistoryButtonTextId;
    private @StringRes int mManageOrHistoryButtonDescriptionId;
    private @StringRes int mMessageStringId;
    private @DrawableRes int mMessageIconId;

    private OnClickListener mClearAllButtonClickListener;

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

    /** Whether the "Clear all" button is currently visible. */
    public boolean isClearAllButtonVisible() {
        return isSecondaryVisible();
    }

    /** See {@link this#setClearAllButtonVisible(boolean, boolean, Consumer)}. */
    public void setClearAllButtonVisible(boolean visible, boolean animate) {
        setClearAllButtonVisible(visible, animate, /* onAnimationEnded = */ null);
    }

    /**
     * Set the visibility of the "Manage"/"History" button to {@code visible}. This is replaced by
     * two separate buttons in the redesign.
     */
    public void setManageOrHistoryButtonVisible(boolean visible) {
        NotifRedesignFooter.assertInLegacyMode();
        mManageOrHistoryButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Set the visibility of the Settings button to {@code visible}. */
    public void setSettingsButtonVisible(boolean visible) {
        if (NotifRedesignFooter.isUnexpectedlyInLegacyMode()) {
            return;
        }
        mSettingsButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Set the visibility of the History button to {@code visible}. */
    public void setHistoryButtonVisible(boolean visible) {
        if (NotifRedesignFooter.isUnexpectedlyInLegacyMode()) {
            return;
        }
        mHistoryButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * Set the visibility of the "Clear all" button to {@code visible}. Animate the change if
     * {@code animate} is true.
     */
    public void setClearAllButtonVisible(boolean visible, boolean animate,
            Consumer<Boolean> onAnimationEnded) {
        setSecondaryVisible(visible, animate, onAnimationEnded);
    }

    /** See {@link this#setShouldBeHidden} below. */
    public boolean shouldBeHidden() {
        return mShouldBeHidden;
    }

    /**
     * Whether this view's visibility should be set to INVISIBLE. Note that this is different from
     * the {@link StackScrollerDecorView#setVisible} method, which in turn handles visibility
     * transitions between VISIBLE and GONE.
     */
    public void setShouldBeHidden(boolean hide) {
        mShouldBeHidden = hide;
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

    /** Set the text label for the "Clear all" button. */
    public void setClearAllButtonText(@StringRes int textId) {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) return;
        if (mClearAllButtonTextId == textId) {
            return; // nothing changed
        }
        mClearAllButtonTextId = textId;
        updateClearAllButtonText();
    }

    private void updateClearAllButtonText() {
        if (mClearAllButtonTextId == 0) {
            return; // not initialized yet
        }
        mClearAllButton.setText(getContext().getString(mClearAllButtonTextId));
    }

    /** Set the accessibility content description for the "Clear all" button. */
    public void setClearAllButtonDescription(@StringRes int contentDescriptionId) {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) {
            return;
        }
        if (mClearAllButtonDescriptionId == contentDescriptionId) {
            return; // nothing changed
        }
        mClearAllButtonDescriptionId = contentDescriptionId;
        updateClearAllButtonDescription();
    }

    private void updateClearAllButtonDescription() {
        if (mClearAllButtonDescriptionId == 0) {
            return; // not initialized yet
        }
        mClearAllButton.setContentDescription(getContext().getString(mClearAllButtonDescriptionId));
    }

    /** Set the text label for the "Manage"/"History" button. */
    public void setManageOrHistoryButtonText(@StringRes int textId) {
        NotifRedesignFooter.assertInLegacyMode();
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) return;
        if (mManageOrHistoryButtonTextId == textId) {
            return; // nothing changed
        }
        mManageOrHistoryButtonTextId = textId;
        updateManageOrHistoryButtonText();
    }

    private void updateManageOrHistoryButtonText() {
        NotifRedesignFooter.assertInLegacyMode();
        if (mManageOrHistoryButtonTextId == 0) {
            return; // not initialized yet
        }
        mManageOrHistoryButton.setText(getContext().getString(mManageOrHistoryButtonTextId));
    }

    /** Set the accessibility content description for the "Clear all" button. */
    public void setManageOrHistoryButtonDescription(@StringRes int contentDescriptionId) {
        NotifRedesignFooter.assertInLegacyMode();
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) {
            return;
        }
        if (mManageOrHistoryButtonDescriptionId == contentDescriptionId) {
            return; // nothing changed
        }
        mManageOrHistoryButtonDescriptionId = contentDescriptionId;
        updateManageOrHistoryButtonDescription();
    }

    private void updateManageOrHistoryButtonDescription() {
        NotifRedesignFooter.assertInLegacyMode();
        if (mManageOrHistoryButtonDescriptionId == 0) {
            return; // not initialized yet
        }
        mManageOrHistoryButton.setContentDescription(
                getContext().getString(mManageOrHistoryButtonDescriptionId));
    }

    /** Set the string for a message to be shown instead of the buttons. */
    public void setMessageString(@StringRes int messageId) {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) return;
        if (mMessageStringId == messageId) {
            return; // nothing changed
        }
        mMessageStringId = messageId;
        updateMessageString();
    }

    private void updateMessageString() {
        if (mMessageStringId == 0) {
            return; // not initialized yet
        }
        String messageString = getContext().getString(mMessageStringId);
        mSeenNotifsFooterTextView.setText(messageString);
    }

    /** Set the icon to be shown before the message (see {@link #setMessageString(int)}). */
    public void setMessageIcon(@DrawableRes int iconId) {
        if (FooterViewRefactor.isUnexpectedlyInLegacyMode()) return;
        if (mMessageIconId == iconId) {
            return; // nothing changed
        }
        mMessageIconId = iconId;
        updateMessageIcon();
    }

    private void updateMessageIcon() {
        if (mMessageIconId == 0) {
            return; // not initialized yet
        }
        int unlockIconSize = getResources()
                .getDimensionPixelSize(R.dimen.notifications_unseen_footer_icon_size);
        @SuppressLint("UseCompatLoadingForDrawables")
        Drawable messageIcon = getContext().getDrawable(mMessageIconId);
        if (messageIcon != null) {
            messageIcon.setBounds(0, 0, unlockIconSize, unlockIconSize);
            mSeenNotifsFooterTextView
                    .setCompoundDrawablesRelative(messageIcon, null, null, null);
        }
    }

    @Override
    protected void onFinishInflate() {
        ColorUpdateLogger colorUpdateLogger = ColorUpdateLogger.getInstance();
        if (colorUpdateLogger != null) {
            colorUpdateLogger.logTriggerEvent("Footer.onFinishInflate()");
        }
        super.onFinishInflate();
        mClearAllButton = (FooterViewButton) findSecondaryView();
        if (NotifRedesignFooter.isEnabled()) {
            mSettingsButton = findViewById(R.id.settings_button);
            mHistoryButton = findViewById(R.id.history_button);
        } else {
            mManageOrHistoryButton = findViewById(R.id.manage_text);
        }
        mSeenNotifsFooterTextView = findViewById(R.id.unlock_prompt_footer);
        if (!FooterViewRefactor.isEnabled()) {
            updateResources();
        }
        updateContent();
        updateColors();
    }

    /** Show a message instead of the footer buttons. */
    public void setFooterLabelVisible(boolean isVisible) {
        // In the refactored code, hiding the buttons is handled in the FooterViewModel
        if (FooterViewRefactor.isEnabled()) {
            if (isVisible) {
                mSeenNotifsFooterTextView.setVisibility(View.VISIBLE);
            } else {
                mSeenNotifsFooterTextView.setVisibility(View.GONE);
            }
        } else {
            if (isVisible) {
                mManageOrHistoryButton.setVisibility(View.GONE);
                mClearAllButton.setVisibility(View.GONE);
                mSeenNotifsFooterTextView.setVisibility(View.VISIBLE);
            } else {
                mManageOrHistoryButton.setVisibility(View.VISIBLE);
                mClearAllButton.setVisibility(View.VISIBLE);
                mSeenNotifsFooterTextView.setVisibility(View.GONE);
            }
        }
    }

    /** Set onClickListener for the notification settings button. */
    public void setSettingsButtonClickListener(OnClickListener listener) {
        if (NotifRedesignFooter.isUnexpectedlyInLegacyMode()) {
            return;
        }
        mSettingsButton.setOnClickListener(listener);
    }

    /** Set onClickListener for the notification history button. */
    public void setHistoryButtonClickListener(OnClickListener listener) {
        if (NotifRedesignFooter.isUnexpectedlyInLegacyMode()) {
            return;
        }
        mHistoryButton.setOnClickListener(listener);
    }

    /**
     * Set onClickListener for the manage/history button. This is replaced by two separate buttons
     * in the redesign.
     */
    public void setManageButtonClickListener(OnClickListener listener) {
        NotifRedesignFooter.assertInLegacyMode();
        mManageOrHistoryButton.setOnClickListener(listener);
    }

    /** Set onClickListener for the clear all (end) button. */
    public void setClearAllButtonClickListener(OnClickListener listener) {
        if (FooterViewRefactor.isEnabled()) {
            if (mClearAllButtonClickListener == listener) return;
            mClearAllButtonClickListener = listener;
        }
        mClearAllButton.setOnClickListener(listener);
    }

    /**
     * Whether the touch is outside the Clear all button.
     *
     * TODO(b/293167744): This is an artifact from the time when we could press underneath the
     * shade to dismiss it. Check if it's safe to remove.
     */
    public boolean isOnEmptySpace(float touchX, float touchY) {
        return touchX < mContent.getX()
                || touchX > mContent.getX() + mContent.getWidth()
                || touchY < mContent.getY()
                || touchY > mContent.getY() + mContent.getHeight();
    }

    /** Show "History" instead of "Manage" on the start button. */
    public void showHistory(boolean showHistory) {
        FooterViewRefactor.assertInLegacyMode();
        if (mShowHistory == showHistory) {
            return;
        }
        mShowHistory = showHistory;
        updateContent();
    }

    private void updateContent() {
        if (FooterViewRefactor.isEnabled()) {
            updateClearAllButtonText();
            updateClearAllButtonDescription();

            if (!NotifRedesignFooter.isEnabled()) {
                updateManageOrHistoryButtonText();
                updateManageOrHistoryButtonDescription();
            }

            updateMessageString();
            updateMessageIcon();
        } else {
            // NOTE: Prior to the refactor, `updateResources` set the class properties to the right
            // string values. It was always being called together with `updateContent`, which
            // deals with actually associating those string values with the correct views
            // (buttons or text).
            // In the new code, the resource IDs are being set in the view binder (through
            // setMessageString and similar setters). The setters themselves now deal with
            // updating both the resource IDs and the views where appropriate (as in, calling
            // `updateMessageString` when the resource ID changes). This eliminates the need for
            // `updateResources`, which will eventually be removed. There are, however, still
            // situations in which we want to update the views even if the resource IDs didn't
            // change, such as configuration changes.
            if (mShowHistory) {
                mManageOrHistoryButton.setText(mManageNotificationHistoryText);
                mManageOrHistoryButton.setContentDescription(mManageNotificationHistoryText);
            } else {
                mManageOrHistoryButton.setText(mManageNotificationText);
                mManageOrHistoryButton.setContentDescription(mManageNotificationText);
            }

            mClearAllButton.setText(R.string.clear_all_notifications_text);
            mClearAllButton.setContentDescription(
                    mContext.getString(R.string.accessibility_clear_all));

            mSeenNotifsFooterTextView.setText(mSeenNotifsFilteredText);
            mSeenNotifsFooterTextView
                    .setCompoundDrawablesRelative(mSeenNotifsFilteredIcon, null, null, null);
        }
    }

    /** Whether the start button shows "History" (true) or "Manage" (false). */
    public boolean isHistoryShown() {
        FooterViewRefactor.assertInLegacyMode();
        return mShowHistory;
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        ColorUpdateLogger colorUpdateLogger = ColorUpdateLogger.getInstance();
        if (colorUpdateLogger != null) {
            colorUpdateLogger.logTriggerEvent("Footer.onConfigurationChanged()");
        }
        super.onConfigurationChanged(newConfig);
        updateColors();
        if (!FooterViewRefactor.isEnabled()) {
            updateResources();
        }
        updateContent();
    }

    /**
     * Update the text and background colors for the current color palette and night mode setting.
     */
    public void updateColors() {
        Resources.Theme theme = mContext.getTheme();
        final @ColorInt int onSurface = Utils.getColorAttrDefaultColor(mContext,
                com.android.internal.R.attr.materialColorOnSurface);
        final Drawable clearAllBg = theme.getDrawable(R.drawable.notif_footer_btn_background);
        final Drawable manageBg = theme.getDrawable(R.drawable.notif_footer_btn_background);
        final @ColorInt int scHigh;
        if (!notificationFooterBackgroundTintOptimization()) {
            scHigh = Utils.getColorAttrDefaultColor(mContext,
                    com.android.internal.R.attr.materialColorSurfaceContainerHigh);
            if (scHigh != 0) {
                final ColorFilter bgColorFilter = new PorterDuffColorFilter(scHigh, SRC_ATOP);
                clearAllBg.setColorFilter(bgColorFilter);
                manageBg.setColorFilter(bgColorFilter);
            }
        } else {
            scHigh = 0;
        }
        mClearAllButton.setBackground(clearAllBg);
        mClearAllButton.setTextColor(onSurface);
        if (NotifRedesignFooter.isEnabled()) {
            mSettingsButton.setBackground(manageBg);
            mSettingsButton.setCompoundDrawableTintList(ColorStateList.valueOf(onSurface));

            mHistoryButton.setBackground(manageBg);
            mHistoryButton.setCompoundDrawableTintList(ColorStateList.valueOf(onSurface));
        } else {
            mManageOrHistoryButton.setBackground(manageBg);
            mManageOrHistoryButton.setTextColor(onSurface);
        }
        mSeenNotifsFooterTextView.setTextColor(onSurface);
        mSeenNotifsFooterTextView.setCompoundDrawableTintList(ColorStateList.valueOf(onSurface));
        ColorUpdateLogger colorUpdateLogger = ColorUpdateLogger.getInstance();
        if (colorUpdateLogger != null) {
            colorUpdateLogger.logEvent("Footer.updateColors()",
                    "textColor(onSurface)=" + hexColorString(onSurface)
                            + " backgroundTint(surfaceContainerHigh)=" + hexColorString(scHigh)
                            + " background=" + DrawableDumpKt.dumpToString(manageBg));
        }
    }

    private void updateResources() {
        FooterViewRefactor.assertInLegacyMode();
        mManageNotificationText = getContext().getString(R.string.manage_notifications_text);
        mManageNotificationHistoryText = getContext()
                .getString(R.string.manage_notifications_history_text);
        int unlockIconSize = getResources()
                .getDimensionPixelSize(R.dimen.notifications_unseen_footer_icon_size);
        mSeenNotifsFilteredText = getContext().getString(R.string.unlock_to_see_notif_text);
        mSeenNotifsFilteredIcon = getContext().getDrawable(R.drawable.ic_friction_lock_closed);
        mSeenNotifsFilteredIcon.setBounds(0, 0, unlockIconSize, unlockIconSize);
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

        /**
         * When true, skip animating Y on the next #animateTo.
         * Once true, remains true until reset in #animateTo.
         */
        public boolean resetY = false;

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
                footerView.setContentVisibleAnimated(!hideContent);
            }
        }

        @Override
        public void animateTo(View child, AnimationProperties properties) {
            if (child instanceof FooterView) {
                // Must set animateY=false before super.animateTo, which checks for animateY
                if (resetY) {
                    properties.getAnimationFilter().animateY = false;
                    resetY = false;
                }
            }
            super.animateTo(child, properties);
        }
    }
}
