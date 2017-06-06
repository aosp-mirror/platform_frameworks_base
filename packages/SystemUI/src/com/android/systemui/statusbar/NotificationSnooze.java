package com.android.systemui.statusbar;
/*
 * Copyright (C) 2017 The Android Open Source Project
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

import java.util.ArrayList;
import java.util.List;

import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper;
import com.android.systemui.plugins.statusbar.NotificationSwipeActionHelper.SnoozeOption;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Typeface;
import android.os.Bundle;
import android.service.notification.SnoozeCriterion;
import android.service.notification.StatusBarNotification;
import android.text.SpannableString;
import android.text.style.StyleSpan;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.systemui.Interpolators;
import com.android.systemui.R;

public class NotificationSnooze extends LinearLayout
        implements NotificationGuts.GutsContent, View.OnClickListener {

    /**
     * If this changes more number increases, more assistant action resId's should be defined for
     * accessibility purposes, see {@link #setSnoozeOptions(List)}
     */
    private static final int MAX_ASSISTANT_SUGGESTIONS = 1;
    private NotificationGuts mGutsContainer;
    private NotificationSwipeActionHelper mSnoozeListener;
    private StatusBarNotification mSbn;

    private TextView mSelectedOptionText;
    private TextView mUndoButton;
    private ImageView mExpandButton;
    private View mDivider;
    private ViewGroup mSnoozeOptionContainer;
    private List<SnoozeOption> mSnoozeOptions;
    private int mCollapsedHeight;
    private SnoozeOption mDefaultOption;
    private SnoozeOption mSelectedOption;
    private boolean mSnoozing;
    private boolean mExpanded;
    private AnimatorSet mExpandAnimation;

    public NotificationSnooze(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mCollapsedHeight = getResources().getDimensionPixelSize(R.dimen.snooze_snackbar_min_height);
        findViewById(R.id.notification_snooze).setOnClickListener(this);
        mSelectedOptionText = (TextView) findViewById(R.id.snooze_option_default);
        mUndoButton = (TextView) findViewById(R.id.undo);
        mUndoButton.setOnClickListener(this);
        mExpandButton = (ImageView) findViewById(R.id.expand_button);
        mDivider = findViewById(R.id.divider);
        mDivider.setAlpha(0f);
        mSnoozeOptionContainer = (ViewGroup) findViewById(R.id.snooze_options);
        mSnoozeOptionContainer.setVisibility(View.INVISIBLE);
        mSnoozeOptionContainer.setAlpha(0f);

        // Create the different options based on list
        mSnoozeOptions = getDefaultSnoozeOptions();
        createOptionViews();

        setSelected(mDefaultOption);
    }

    @Override
    public void onInitializeAccessibilityEvent(AccessibilityEvent event) {
        super.onInitializeAccessibilityEvent(event);
        if (mGutsContainer != null && mGutsContainer.isExposed()) {
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                event.getText().add(mSelectedOptionText.getText());
            }
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(new AccessibilityAction(R.id.action_snooze_undo,
                getResources().getString(R.string.snooze_undo)));
        int count = mSnoozeOptions.size();
        for (int i = 0; i < count; i++) {
            AccessibilityAction action = mSnoozeOptions.get(i).getAccessibilityAction();
            if (action != null) {
                info.addAction(action);
            }
        }
    }

    @Override
    public boolean performAccessibilityActionInternal(int action, Bundle arguments) {
        if (super.performAccessibilityActionInternal(action, arguments)) {
            return true;
        }
        if (action == R.id.action_snooze_undo) {
            undoSnooze(mUndoButton);
            return true;
        }
        for (int i = 0; i < mSnoozeOptions.size(); i++) {
            SnoozeOption so = mSnoozeOptions.get(i);
            if (so.getAccessibilityAction() != null
                    && so.getAccessibilityAction().getId() == action) {
                setSelected(so);
                return true;
            }
        }
        return false;
    }

    public void setSnoozeOptions(final List<SnoozeCriterion> snoozeList) {
        if (snoozeList == null) {
            return;
        }
        mSnoozeOptions.clear();
        mSnoozeOptions = getDefaultSnoozeOptions();
        final int count = Math.min(MAX_ASSISTANT_SUGGESTIONS, snoozeList.size());
        for (int i = 0; i < count; i++) {
            SnoozeCriterion sc = snoozeList.get(i);
            AccessibilityAction action = new AccessibilityAction(
                    R.id.action_snooze_assistant_suggestion_1, sc.getExplanation());
            mSnoozeOptions.add(new NotificationSnoozeOption(sc, 0, sc.getExplanation(),
                    sc.getConfirmation(), action));
        }
        createOptionViews();
    }

    public boolean isExpanded() {
        return mExpanded;
    }

    public void setSnoozeListener(NotificationSwipeActionHelper listener) {
        mSnoozeListener = listener;
    }

    public void setStatusBarNotification(StatusBarNotification sbn) {
        mSbn = sbn;
    }

    private ArrayList<SnoozeOption> getDefaultSnoozeOptions() {
        ArrayList<SnoozeOption> options = new ArrayList<>();

        options.add(createOption(15 /* minutes */, R.id.action_snooze_15_min));
        options.add(createOption(30 /* minutes */, R.id.action_snooze_30_min));
        mDefaultOption = createOption(60 /* minutes */, R.id.action_snooze_1_hour);
        options.add(mDefaultOption);
        options.add(createOption(60 * 2 /* minutes */, R.id.action_snooze_2_hours));
        return options;
    }

    private SnoozeOption createOption(int minutes, int accessibilityActionId) {
        Resources res = getResources();
        boolean showInHours = minutes >= 60;
        int pluralResId = showInHours
                ? R.plurals.snoozeHourOptions
                : R.plurals.snoozeMinuteOptions;
        int count = showInHours ? (minutes / 60) : minutes;
        String description = res.getQuantityString(pluralResId, count, count);
        String resultText = String.format(res.getString(R.string.snoozed_for_time), description);
        SpannableString string = new SpannableString(resultText);
        string.setSpan(new StyleSpan(Typeface.BOLD),
                resultText.length() - description.length(), resultText.length(), 0 /* flags */);
        AccessibilityAction action = new AccessibilityAction(accessibilityActionId, description);
        return new NotificationSnoozeOption(null, minutes, description, string,
                action);
    }

    private void createOptionViews() {
        mSnoozeOptionContainer.removeAllViews();
        LayoutInflater inflater = (LayoutInflater) getContext().getSystemService(
                Context.LAYOUT_INFLATER_SERVICE);
        for (int i = 0; i < mSnoozeOptions.size(); i++) {
            SnoozeOption option = mSnoozeOptions.get(i);
            TextView tv = (TextView) inflater.inflate(R.layout.notification_snooze_option,
                    mSnoozeOptionContainer, false);
            mSnoozeOptionContainer.addView(tv);
            tv.setText(option.getDescription());
            tv.setTag(option);
            tv.setOnClickListener(this);
        }
    }

    private void hideSelectedOption() {
        final int childCount = mSnoozeOptionContainer.getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = mSnoozeOptionContainer.getChildAt(i);
            child.setVisibility(child.getTag() == mSelectedOption ? View.GONE : View.VISIBLE);
        }
    }

    private void showSnoozeOptions(boolean show) {
        int drawableId = show ? com.android.internal.R.drawable.ic_collapse_notification
                : com.android.internal.R.drawable.ic_expand_notification;
        mExpandButton.setImageResource(drawableId);
        if (mExpanded != show) {
            mExpanded = show;
            animateSnoozeOptions(show);
            if (mGutsContainer != null) {
                mGutsContainer.onHeightChanged();
            }
        }
    }

    private void animateSnoozeOptions(boolean show) {
        if (mExpandAnimation != null) {
            mExpandAnimation.cancel();
        }
        ObjectAnimator dividerAnim = ObjectAnimator.ofFloat(mDivider, View.ALPHA,
                mDivider.getAlpha(), show ? 1f : 0f);
        ObjectAnimator optionAnim = ObjectAnimator.ofFloat(mSnoozeOptionContainer, View.ALPHA,
                mSnoozeOptionContainer.getAlpha(), show ? 1f : 0f);
        mSnoozeOptionContainer.setVisibility(View.VISIBLE);
        mExpandAnimation = new AnimatorSet();
        mExpandAnimation.playTogether(dividerAnim, optionAnim);
        mExpandAnimation.setDuration(150);
        mExpandAnimation.setInterpolator(show ? Interpolators.ALPHA_IN : Interpolators.ALPHA_OUT);
        mExpandAnimation.addListener(new AnimatorListenerAdapter() {
            boolean cancelled = false;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!show && !cancelled) {
                    mSnoozeOptionContainer.setVisibility(View.INVISIBLE);
                    mSnoozeOptionContainer.setAlpha(0f);
                }
            }
        });
        mExpandAnimation.start();
    }

    private void setSelected(SnoozeOption option) {
        mSelectedOption = option;
        mSelectedOptionText.setText(option.getConfirmation());
        showSnoozeOptions(false);
        hideSelectedOption();
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    }

    @Override
    public void onClick(View v) {
        if (mGutsContainer != null) {
            mGutsContainer.resetFalsingCheck();
        }
        final int id = v.getId();
        final SnoozeOption tag = (SnoozeOption) v.getTag();
        if (tag != null) {
            setSelected(tag);
        } else if (id == R.id.notification_snooze) {
            // Toggle snooze options
            showSnoozeOptions(!mExpanded);
        } else {
            // Undo snooze was selected
            undoSnooze(v);
        }
    }

    private void undoSnooze(View v) {
        mSelectedOption = null;
        int[] parentLoc = new int[2];
        int[] targetLoc = new int[2];
        mGutsContainer.getLocationOnScreen(parentLoc);
        v.getLocationOnScreen(targetLoc);
        final int centerX = v.getWidth() / 2;
        final int centerY = v.getHeight() / 2;
        final int x = targetLoc[0] - parentLoc[0] + centerX;
        final int y = targetLoc[1] - parentLoc[1] + centerY;
        showSnoozeOptions(false);
        mGutsContainer.closeControls(x, y, false /* save */, false /* force */);
    }

    @Override
    public int getActualHeight() {
        return mExpanded ? getHeight() : mCollapsedHeight;
    }

    @Override
    public boolean willBeRemoved() {
        return mSnoozing;
    }

    @Override
    public View getContentView() {
        // Reset the view before use
        setSelected(mDefaultOption);
        return this;
    }

    @Override
    public void setGutsParent(NotificationGuts guts) {
        mGutsContainer = guts;
    }

    @Override
    public boolean handleCloseControls(boolean save, boolean force) {
        if (mExpanded && !force) {
            // Collapse expanded state on outside touch
            showSnoozeOptions(false);
            return true;
        } else if (mSnoozeListener != null && mSelectedOption != null) {
            // Snooze option selected so commit it
            mSnoozing = true;
            mSnoozeListener.snooze(mSbn, mSelectedOption);
            return true;
        } else {
            // The view should actually be closed
            setSelected(mSnoozeOptions.get(0));
            return false; // Return false here so that guts handles closing the view
        }
    }

    @Override
    public boolean isLeavebehind() {
        return true;
    }

    public class NotificationSnoozeOption implements SnoozeOption {
        private SnoozeCriterion mCriterion;
        private int mMinutesToSnoozeFor;
        private CharSequence mDescription;
        private CharSequence mConfirmation;
        private AccessibilityAction mAction;

        public NotificationSnoozeOption(SnoozeCriterion sc, int minToSnoozeFor,
                CharSequence description,
                CharSequence confirmation, AccessibilityAction action) {
            mCriterion = sc;
            mMinutesToSnoozeFor = minToSnoozeFor;
            mDescription = description;
            mConfirmation = confirmation;
            mAction = action;
        }

        @Override
        public SnoozeCriterion getSnoozeCriterion() {
            return mCriterion;
        }

        @Override
        public CharSequence getDescription() {
            return mDescription;
        }

        @Override
        public CharSequence getConfirmation() {
            return mConfirmation;
        }

        @Override
        public int getMinutesToSnoozeFor() {
            return mMinutesToSnoozeFor;
        }

        @Override
        public AccessibilityAction getAccessibilityAction() {
            return mAction;
        }

    }
}
