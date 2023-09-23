/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.settingslib.widget;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.utils.BuildCompatUtils;
import java.util.ArrayList;
import java.util.List;

import com.android.settingslib.widget.preference.actionbuttons.R;

/**
 * This preference provides a four buttons layout with Settings style.
 * It looks like below
 *
 *  ---------------------------------------
 * - button1 | button2 | button3 | button4 -
 *  ---------------------------------------
 *
 * User can set title / icon / click listener for each button.
 *
 * By default, four buttons are visible.
 * However, there are two cases which button should be invisible(View.GONE).
 *
 * 1. User sets invisible for button. ex: ActionButtonPreference.setButton1Visible(false)
 * 2. User doesn't set any title or icon for button.
 */
public class ActionButtonsPreference extends Preference {

    private static final String TAG = "ActionButtonPreference";
    private static final boolean mIsAtLeastS = BuildCompatUtils.isAtLeastS();
    private static final int SINGLE_BUTTON_STYLE = 1;
    private static final int TWO_BUTTONS_STYLE = 2;
    private static final int THREE_BUTTONS_STYLE = 3;
    private static final int FOUR_BUTTONS_STYLE = 4;

    private final ButtonInfo mButton1Info = new ButtonInfo();
    private final ButtonInfo mButton2Info = new ButtonInfo();
    private final ButtonInfo mButton3Info = new ButtonInfo();
    private final ButtonInfo mButton4Info = new ButtonInfo();
    private final List<ButtonInfo> mVisibleButtonInfos = new ArrayList<>(4);
    private final List<Drawable> mBtnBackgroundStyle1 = new ArrayList<>(1);
    private final List<Drawable> mBtnBackgroundStyle2 = new ArrayList<>(2);
    private final List<Drawable> mBtnBackgroundStyle3 = new ArrayList<>(3);
    private final List<Drawable> mBtnBackgroundStyle4 = new ArrayList<>(4);

    private View mDivider1;
    private View mDivider2;
    private View mDivider3;

    public ActionButtonsPreference(Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public ActionButtonsPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public ActionButtonsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public ActionButtonsPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setLayoutResource(R.layout.settingslib_action_buttons);
        setSelectable(false);

        final Resources res = getContext().getResources();
        fetchDrawableArray(mBtnBackgroundStyle1, res.obtainTypedArray(R.array.background_style1));
        fetchDrawableArray(mBtnBackgroundStyle2, res.obtainTypedArray(R.array.background_style2));
        fetchDrawableArray(mBtnBackgroundStyle3, res.obtainTypedArray(R.array.background_style3));
        fetchDrawableArray(mBtnBackgroundStyle4, res.obtainTypedArray(R.array.background_style4));
    }

    private void fetchDrawableArray(List<Drawable> drawableList, TypedArray typedArray) {
        for (int i = 0; i < typedArray.length(); i++) {
            drawableList.add(
                    getContext().getDrawable(typedArray.getResourceId(i, 0 /* defValue */)));
        }
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        holder.setDividerAllowedAbove(!mIsAtLeastS);
        holder.setDividerAllowedBelow(!mIsAtLeastS);

        mButton1Info.mButton = (Button) holder.findViewById(R.id.button1);
        mButton2Info.mButton = (Button) holder.findViewById(R.id.button2);
        mButton3Info.mButton = (Button) holder.findViewById(R.id.button3);
        mButton4Info.mButton = (Button) holder.findViewById(R.id.button4);

        mDivider1 = holder.findViewById(R.id.divider1);
        mDivider2 = holder.findViewById(R.id.divider2);
        mDivider3 = holder.findViewById(R.id.divider3);

        mButton1Info.setUpButton();
        mButton2Info.setUpButton();
        mButton3Info.setUpButton();
        mButton4Info.setUpButton();

        // Clear info list to avoid duplicate setup.
        if (!mVisibleButtonInfos.isEmpty()) {
            mVisibleButtonInfos.clear();
        }
        updateLayout();
    }

    @Override
    protected void notifyChanged() {
        super.notifyChanged();

        // Update buttons background and layout when notified and visible button list exist.
        if (!mVisibleButtonInfos.isEmpty()) {
            mVisibleButtonInfos.clear();
            updateLayout();
        }
    }

    private void updateLayout() {
        // Add visible button into list only when platform version is newer than S.
        if (mButton1Info.isVisible() && mIsAtLeastS) {
            mVisibleButtonInfos.add(mButton1Info);
        }
        if (mButton2Info.isVisible() && mIsAtLeastS) {
            mVisibleButtonInfos.add(mButton2Info);
        }
        if (mButton3Info.isVisible() && mIsAtLeastS) {
            mVisibleButtonInfos.add(mButton3Info);
        }
        if (mButton4Info.isVisible() && mIsAtLeastS) {
            mVisibleButtonInfos.add(mButton4Info);
        }

        final boolean isRtl = getContext().getResources().getConfiguration()
                .getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        switch (mVisibleButtonInfos.size()) {
            case SINGLE_BUTTON_STYLE :
                if (isRtl) {
                    setupRtlBackgrounds(mVisibleButtonInfos, mBtnBackgroundStyle1);
                } else {
                    setupBackgrounds(mVisibleButtonInfos, mBtnBackgroundStyle1);
                }
                break;
            case TWO_BUTTONS_STYLE :
                if (isRtl) {
                    setupRtlBackgrounds(mVisibleButtonInfos, mBtnBackgroundStyle2);
                } else {
                    setupBackgrounds(mVisibleButtonInfos, mBtnBackgroundStyle2);
                }
                break;
            case THREE_BUTTONS_STYLE :
                if (isRtl) {
                    setupRtlBackgrounds(mVisibleButtonInfos, mBtnBackgroundStyle3);
                } else {
                    setupBackgrounds(mVisibleButtonInfos, mBtnBackgroundStyle3);
                }
                break;
            case FOUR_BUTTONS_STYLE :
                if (isRtl) {
                    setupRtlBackgrounds(mVisibleButtonInfos, mBtnBackgroundStyle4);
                } else {
                    setupBackgrounds(mVisibleButtonInfos, mBtnBackgroundStyle4);
                }
                break;
            default:
                Log.e(TAG, "No visible buttons info, skip background settings.");
                break;
        }

        setupDivider1();
        setupDivider2();
        setupDivider3();
    }

    private void setupBackgrounds(
            List<ButtonInfo> buttonInfoList, List<Drawable> buttonBackgroundStyles) {
        for (int i = 0; i < buttonBackgroundStyles.size(); i++) {
            buttonInfoList.get(i).mButton.setBackground(buttonBackgroundStyles.get(i));
        }
    }

    private void setupRtlBackgrounds(
            List<ButtonInfo> buttonInfoList, List<Drawable> buttonBackgroundStyles) {
        for (int i = buttonBackgroundStyles.size() - 1; i >= 0; i--) {
            buttonInfoList.get(buttonBackgroundStyles.size() - 1 - i)
                    .mButton.setBackground(buttonBackgroundStyles.get(i));
        }
    }

    /**
     * Set the visibility state of button1.
     */
    public ActionButtonsPreference setButton1Visible(boolean isVisible) {
        if (isVisible != mButton1Info.mIsVisible) {
            mButton1Info.mIsVisible = isVisible;
            notifyChanged();
        }
        return this;
    }

    /**
     * Sets the text to be displayed in button1.
     */
    public ActionButtonsPreference setButton1Text(@StringRes int textResId) {
        final String newText = getContext().getString(textResId);
        if (!TextUtils.equals(newText, mButton1Info.mText)) {
            mButton1Info.mText = newText;
            notifyChanged();
        }
        return this;
    }

    /**
     * Sets the drawable to be displayed above of text in button1.
     */
    public ActionButtonsPreference setButton1Icon(@DrawableRes int iconResId) {
        if (iconResId == 0) {
            return this;
        }

        final Drawable icon;
        try {
            icon = getContext().getDrawable(iconResId);
            mButton1Info.mIcon = icon;
            notifyChanged();
        } catch (Resources.NotFoundException exception) {
            Log.e(TAG, "Resource does not exist: " + iconResId);
        }
        return this;
    }

    /**
     * Set the enabled state of button1.
     */
    public ActionButtonsPreference setButton1Enabled(boolean isEnabled) {
        if (isEnabled != mButton1Info.mIsEnabled) {
            mButton1Info.mIsEnabled = isEnabled;
            notifyChanged();
        }
        return this;
    }

    /**
     * Register a callback to be invoked when button1 is clicked.
     */
    public ActionButtonsPreference setButton1OnClickListener(
            View.OnClickListener listener) {
        if (listener != mButton1Info.mListener) {
            mButton1Info.mListener = listener;
            notifyChanged();
        }
        return this;
    }

    /**
     * Set the visibility state of button2.
     */
    public ActionButtonsPreference setButton2Visible(boolean isVisible) {
        if (isVisible != mButton2Info.mIsVisible) {
            mButton2Info.mIsVisible = isVisible;
            notifyChanged();
        }
        return this;
    }

    /**
     * Sets the text to be displayed in button2.
     */
    public ActionButtonsPreference setButton2Text(@StringRes int textResId) {
        final String newText = getContext().getString(textResId);
        if (!TextUtils.equals(newText, mButton2Info.mText)) {
            mButton2Info.mText = newText;
            notifyChanged();
        }
        return this;
    }

    /**
     * Sets the drawable to be displayed above of text in button2.
     */
    public ActionButtonsPreference setButton2Icon(@DrawableRes int iconResId) {
        if (iconResId == 0) {
            return this;
        }

        final Drawable icon;
        try {
            icon = getContext().getDrawable(iconResId);
            mButton2Info.mIcon = icon;
            notifyChanged();
        } catch (Resources.NotFoundException exception) {
            Log.e(TAG, "Resource does not exist: " + iconResId);
        }
        return this;
    }

    /**
     * Set the enabled state of button2.
     */
    public ActionButtonsPreference setButton2Enabled(boolean isEnabled) {
        if (isEnabled != mButton2Info.mIsEnabled) {
            mButton2Info.mIsEnabled = isEnabled;
            notifyChanged();
        }
        return this;
    }

    /**
     * Register a callback to be invoked when button2 is clicked.
     */
    public ActionButtonsPreference setButton2OnClickListener(
            View.OnClickListener listener) {
        if (listener != mButton2Info.mListener) {
            mButton2Info.mListener = listener;
            notifyChanged();
        }
        return this;
    }

    /**
     * Set the visibility state of button3.
     */
    public ActionButtonsPreference setButton3Visible(boolean isVisible) {
        if (isVisible != mButton3Info.mIsVisible) {
            mButton3Info.mIsVisible = isVisible;
            notifyChanged();
        }
        return this;
    }

    /**
     * Sets the text to be displayed in button3.
     */
    public ActionButtonsPreference setButton3Text(@StringRes int textResId) {
        final String newText = getContext().getString(textResId);
        if (!TextUtils.equals(newText, mButton3Info.mText)) {
            mButton3Info.mText = newText;
            notifyChanged();
        }
        return this;
    }

    /**
     * Sets the drawable to be displayed above of text in button3.
     */
    public ActionButtonsPreference setButton3Icon(@DrawableRes int iconResId) {
        if (iconResId == 0) {
            return this;
        }

        final Drawable icon;
        try {
            icon = getContext().getDrawable(iconResId);
            mButton3Info.mIcon = icon;
            notifyChanged();
        } catch (Resources.NotFoundException exception) {
            Log.e(TAG, "Resource does not exist: " + iconResId);
        }
        return this;
    }

    /**
     * Set the enabled state of button3.
     */
    public ActionButtonsPreference setButton3Enabled(boolean isEnabled) {
        if (isEnabled != mButton3Info.mIsEnabled) {
            mButton3Info.mIsEnabled = isEnabled;
            notifyChanged();
        }
        return this;
    }

    /**
     * Register a callback to be invoked when button3 is clicked.
     */
    public ActionButtonsPreference setButton3OnClickListener(
            View.OnClickListener listener) {
        if (listener != mButton3Info.mListener) {
            mButton3Info.mListener = listener;
            notifyChanged();
        }
        return this;
    }

    /**
     * Set the visibility state of button4.
     */
    public ActionButtonsPreference setButton4Visible(boolean isVisible) {
        if (isVisible != mButton4Info.mIsVisible) {
            mButton4Info.mIsVisible = isVisible;
            notifyChanged();
        }
        return this;
    }

    /**
     * Sets the text to be displayed in button4.
     */
    public ActionButtonsPreference setButton4Text(@StringRes int textResId) {
        final String newText = getContext().getString(textResId);
        if (!TextUtils.equals(newText, mButton4Info.mText)) {
            mButton4Info.mText = newText;
            notifyChanged();
        }
        return this;
    }

    /**
     * Sets the drawable to be displayed above of text in button4.
     */
    public ActionButtonsPreference setButton4Icon(@DrawableRes int iconResId) {
        if (iconResId == 0) {
            return this;
        }

        final Drawable icon;
        try {
            icon = getContext().getDrawable(iconResId);
            mButton4Info.mIcon = icon;
            notifyChanged();
        } catch (Resources.NotFoundException exception) {
            Log.e(TAG, "Resource does not exist: " + iconResId);
        }
        return this;
    }

    /**
     * Set the enabled state of button4.
     */
    public ActionButtonsPreference setButton4Enabled(boolean isEnabled) {
        if (isEnabled != mButton4Info.mIsEnabled) {
            mButton4Info.mIsEnabled = isEnabled;
            notifyChanged();
        }
        return this;
    }

    /**
     * Register a callback to be invoked when button4 is clicked.
     */
    public ActionButtonsPreference setButton4OnClickListener(
            View.OnClickListener listener) {
        if (listener != mButton4Info.mListener) {
            mButton4Info.mListener = listener;
            notifyChanged();
        }
        return this;
    }

    private void setupDivider1() {
        // Display divider1 only if button1 and button2 is visible
        if (mDivider1 != null && mButton1Info.isVisible() && mButton2Info.isVisible()) {
            mDivider1.setVisibility(View.VISIBLE);
        }
    }

    private void setupDivider2() {
        // Display divider2 only if button3 is visible and button2 or button3 is visible
        if (mDivider2 != null && mButton3Info.isVisible()
                && (mButton1Info.isVisible() || mButton2Info.isVisible())) {
            mDivider2.setVisibility(View.VISIBLE);
        }
    }

    private void setupDivider3() {
        // Display divider3 only if button4 is visible and 2 visible buttons at least
        if (mDivider3 != null && mVisibleButtonInfos.size() > 1 && mButton4Info.isVisible()) {
            mDivider3.setVisibility(View.VISIBLE);
        }
    }

    static class ButtonInfo {
        private Button mButton;
        private CharSequence mText;
        private Drawable mIcon;
        private View.OnClickListener mListener;
        private boolean mIsEnabled = true;
        private boolean mIsVisible = true;

        void setUpButton() {
            mButton.setText(mText);
            mButton.setOnClickListener(mListener);
            mButton.setEnabled(mIsEnabled);
            mButton.setCompoundDrawablesWithIntrinsicBounds(
                    null /* left */, mIcon /* top */, null /* right */, null /* bottom */);

            if (shouldBeVisible()) {
                mButton.setVisibility(View.VISIBLE);
            } else {
                mButton.setVisibility(View.GONE);
            }
        }

        boolean isVisible() {
            return mButton.getVisibility() == View.VISIBLE;
        }

        /**
         * By default, four buttons are visible.
         * However, there are two cases which button should be invisible.
         *
         * 1. User set invisible for this button. ex: mIsVisible = false.
         * 2. User didn't set any title or icon.
         */
        private boolean shouldBeVisible() {
            return mIsVisible && (!TextUtils.isEmpty(mText) || mIcon != null);
        }
    }
}
