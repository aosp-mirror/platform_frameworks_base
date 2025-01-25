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
import static com.android.systemui.Flags.notificationShadeBlur;
import static com.android.systemui.util.ColorUtilKt.hexColorString;

import android.annotation.ColorInt;
import android.annotation.DrawableRes;
import android.annotation.StringRes;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.util.IndentingPrintWriter;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.android.internal.graphics.ColorUtils;
import com.android.systemui.common.shared.colors.SurfaceEffectColors;
import com.android.systemui.res.R;
import com.android.systemui.scene.shared.flag.SceneContainerFlag;
import com.android.systemui.statusbar.notification.ColorUpdateLogger;
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

    // Footer label
    private TextView mSeenNotifsFooterTextView;

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
            // TODO: b/375010573 - update dumps for redesign
            pw.println("visibility: " + DumpUtilsKt.visibilityString(getVisibility()));
            if (mManageOrHistoryButton != null)
                pw.println("mManageOrHistoryButton visibility: "
                        + DumpUtilsKt.visibilityString(mManageOrHistoryButton.getVisibility()));
            if (mClearAllButton != null)
                pw.println("mClearAllButton visibility: "
                        + DumpUtilsKt.visibilityString(mClearAllButton.getVisibility()));
        });
    }

    /** Set the text label for the "Clear all" button. */
    public void setClearAllButtonText(@StringRes int textId) {
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
        updateContent();
        updateColors();
    }

    /** Show a message instead of the footer buttons. */
    public void setFooterLabelVisible(boolean isVisible) {
        // Note: hiding the buttons is handled in the FooterViewModel
        if (isVisible) {
            mSeenNotifsFooterTextView.setVisibility(View.VISIBLE);
        } else {
            mSeenNotifsFooterTextView.setVisibility(View.GONE);
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
        if (mClearAllButtonClickListener == listener) return;
        mClearAllButtonClickListener = listener;
        mClearAllButton.setOnClickListener(listener);
    }

    /**
     * Whether the touch is outside the Clear all button.
     */
    public boolean isOnEmptySpace(float touchX, float touchY) {
        SceneContainerFlag.assertInLegacyMode();
        return touchX < mContent.getX()
                || touchX > mContent.getX() + mContent.getWidth()
                || touchY < mContent.getY()
                || touchY > mContent.getY() + mContent.getHeight();
    }

    private void updateContent() {
        updateClearAllButtonText();
        updateClearAllButtonDescription();

        if (!NotifRedesignFooter.isEnabled()) {
            updateManageOrHistoryButtonText();
            updateManageOrHistoryButtonDescription();
        }

        updateMessageString();
        updateMessageIcon();
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        ColorUpdateLogger colorUpdateLogger = ColorUpdateLogger.getInstance();
        if (colorUpdateLogger != null) {
            colorUpdateLogger.logTriggerEvent("Footer.onConfigurationChanged()");
        }
        super.onConfigurationChanged(newConfig);
        updateColors();
        updateContent();
    }

    /**
     * Update the text and background colors for the current color palette and night mode setting.
     */
    public void updateColors() {
        Resources.Theme theme = mContext.getTheme();
        final @ColorInt int onSurface = mContext.getColor(
                com.android.internal.R.color.materialColorOnSurface);
        // Same resource, separate drawables to prevent touch effects from showing on the wrong
        // button.
        final Drawable clearAllBg = theme.getDrawable(R.drawable.notif_footer_btn_background);
        final Drawable settingsBg = theme.getDrawable(R.drawable.notif_footer_btn_background);
        final Drawable historyBg = NotifRedesignFooter.isEnabled()
                ? theme.getDrawable(R.drawable.notif_footer_btn_background) : null;
        final @ColorInt int scHigh;

        if (!notificationFooterBackgroundTintOptimization()) {
            if (notificationShadeBlur()) {
                Color backgroundColor = Color.valueOf(
                        SurfaceEffectColors.surfaceEffect0(getResources()));
                scHigh = ColorUtils.setAlphaComponent(backgroundColor.toArgb(), 0xFF);
                // Apply alpha on background drawables.
                int backgroundAlpha = (int) (backgroundColor.alpha() * 0xFF);
                clearAllBg.setAlpha(backgroundAlpha);
                settingsBg.setAlpha(backgroundAlpha);
                if (historyBg != null) {
                    historyBg.setAlpha(backgroundAlpha);
                }
            } else {
                scHigh = mContext.getColor(
                        com.android.internal.R.color.materialColorSurfaceContainerHigh);
            }
            if (scHigh != 0) {
                final ColorFilter bgColorFilter = new PorterDuffColorFilter(scHigh, SRC_ATOP);
                clearAllBg.setColorFilter(bgColorFilter);
                settingsBg.setColorFilter(bgColorFilter);
                if (NotifRedesignFooter.isEnabled()) {
                    historyBg.setColorFilter(bgColorFilter);
                }
            }
        } else {
            scHigh = 0;
        }
        mClearAllButton.setBackground(clearAllBg);
        mClearAllButton.setTextColor(onSurface);
        if (NotifRedesignFooter.isEnabled()) {
            mSettingsButton.setBackground(settingsBg);
            mSettingsButton.setCompoundDrawableTintList(ColorStateList.valueOf(onSurface));

            mHistoryButton.setBackground(historyBg);
            mHistoryButton.setCompoundDrawableTintList(ColorStateList.valueOf(onSurface));
        } else {
            mManageOrHistoryButton.setBackground(settingsBg);
            mManageOrHistoryButton.setTextColor(onSurface);
        }
        mSeenNotifsFooterTextView.setTextColor(onSurface);
        mSeenNotifsFooterTextView.setCompoundDrawableTintList(ColorStateList.valueOf(onSurface));
        ColorUpdateLogger colorUpdateLogger = ColorUpdateLogger.getInstance();
        if (colorUpdateLogger != null) {
            colorUpdateLogger.logEvent("Footer.updateColors()",
                    "textColor(onSurface)=" + hexColorString(onSurface)
                            + " backgroundTint(surfaceContainerHigh)=" + hexColorString(scHigh)
                            + " background=" + DrawableDumpKt.dumpToString(settingsBg));
        }
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
