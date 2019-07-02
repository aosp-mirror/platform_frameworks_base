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

/**
 * This preference provides a four buttons layout with Settings style.
 * It looks like below
 *
 * --------------------------------------------------
 * button1     | button2   | button3   | button4   |
 * --------------------------------------------------
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
    private final ButtonInfo mButton1Info = new ButtonInfo();
    private final ButtonInfo mButton2Info = new ButtonInfo();
    private final ButtonInfo mButton3Info = new ButtonInfo();
    private final ButtonInfo mButton4Info = new ButtonInfo();

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
        setLayoutResource(R.layout.settings_action_buttons);
        setSelectable(false);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.setDividerAllowedAbove(true);
        holder.setDividerAllowedBelow(true);

        mButton1Info.mButton = (Button) holder.findViewById(R.id.button1);
        mButton2Info.mButton = (Button) holder.findViewById(R.id.button2);
        mButton3Info.mButton = (Button) holder.findViewById(R.id.button3);
        mButton4Info.mButton = (Button) holder.findViewById(R.id.button4);

        mButton1Info.setUpButton();
        mButton2Info.setUpButton();
        mButton3Info.setUpButton();
        mButton4Info.setUpButton();
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
