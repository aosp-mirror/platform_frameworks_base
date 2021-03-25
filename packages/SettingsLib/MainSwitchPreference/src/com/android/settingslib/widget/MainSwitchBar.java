/*
 * Copyright (C) 2020 The Android Open Source Project
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
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import androidx.core.content.res.TypedArrayUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * MainSwitchBar is a View with a customized Switch.
 * This component is used as the main switch of the page
 * to enable or disable the prefereces on the page.
 */
public class MainSwitchBar extends LinearLayout implements CompoundButton.OnCheckedChangeListener {

    private final List<OnMainSwitchChangeListener> mSwitchChangeListeners = new ArrayList<>();

    private View mAboveDivider;
    private View mBelowDivider;
    protected TextView mTextView;
    protected Switch mSwitch;

    public MainSwitchBar(Context context) {
        this(context, null);
    }

    public MainSwitchBar(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MainSwitchBar(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public MainSwitchBar(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        LayoutInflater.from(context).inflate(resourceId(context, "layout", "main_switch_bar"),
                this);

        setFocusable(true);
        setClickable(true);

        mTextView = (TextView) findViewById(R.id.switch_text);
        mSwitch = (Switch) findViewById(android.R.id.switch_widget);

        addOnSwitchChangeListener((switchView, isChecked) -> setChecked(isChecked));

        setChecked(mSwitch.isChecked());

        if (attrs != null) {
            final TypedArray a = context.obtainStyledAttributes(attrs,
                    androidx.preference.R.styleable.Preference, 0 /*defStyleAttr*/,
                    0 /*defStyleRes*/);
            final CharSequence title = TypedArrayUtils.getText(a,
                    androidx.preference.R.styleable.Preference_title,
                    androidx.preference.R.styleable.Preference_android_title);
            setTitle(title);
            a.recycle();
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        propagateChecked(isChecked);
    }

    @Override
    public boolean performClick() {
        return mSwitch.performClick();
    }

    /**
     * Update the switch status
     */
    public void setChecked(boolean checked) {
        if (mSwitch != null) {
            mSwitch.setChecked(checked);
        }
    }

    /**
     * Return the status of the Switch
     */
    public boolean isChecked() {
        return mSwitch.isChecked();
    }

    /**
     * Return the Switch
     */
    public final Switch getSwitch() {
        return mSwitch;
    }

    /**
     * Set the title text
     */
    public void setTitle(CharSequence text) {
        if (mTextView != null) {
            mTextView.setText(text);
        }
    }

    /**
     * Show the MainSwitchBar
     */
    public void show() {
        setVisibility(View.VISIBLE);
        mSwitch.setOnCheckedChangeListener(this);
    }

    /**
     * Hide the MainSwitchBar
     */
    public void hide() {
        if (isShowing()) {
            setVisibility(View.GONE);
            mSwitch.setOnCheckedChangeListener(null);
        }
    }

    /**
     * Return the displaying status of MainSwitchBar
     */
    public boolean isShowing() {
        return (getVisibility() == View.VISIBLE);
    }

    /**
     * Adds a listener for switch changes
     */
    public void addOnSwitchChangeListener(OnMainSwitchChangeListener listener) {
        if (!mSwitchChangeListeners.contains(listener)) {
            mSwitchChangeListeners.add(listener);
        }
    }

    /**
     * Remove a listener for switch changes
     */
    public void removeOnSwitchChangeListener(OnMainSwitchChangeListener listener) {
        if (mSwitchChangeListeners.contains(listener)) {
            mSwitchChangeListeners.remove(listener);
        }
    }

    /**
     * Enable or disable the text and switch.
     */
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        mTextView.setEnabled(enabled);
        mSwitch.setEnabled(enabled);
    }

    private void propagateChecked(boolean isChecked) {
        final int count = mSwitchChangeListeners.size();
        for (int n = 0; n < count; n++) {
            mSwitchChangeListeners.get(n).onSwitchChanged(mSwitch, isChecked);
        }
    }

    static class SavedState extends BaseSavedState {
        boolean mChecked;
        boolean mVisible;

        SavedState(Parcelable superState) {
            super(superState);
        }

        /**
         * Constructor called from {@link #CREATOR}
         */
        private SavedState(Parcel in) {
            super(in);
            mChecked = (Boolean) in.readValue(null);
            mVisible = (Boolean) in.readValue(null);
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeValue(mChecked);
            out.writeValue(mVisible);
        }

        @Override
        public String toString() {
            return "MainSwitchBar.SavedState{"
                    + Integer.toHexString(System.identityHashCode(this))
                    + " checked=" + mChecked
                    + " visible=" + mVisible + "}";
        }

        public static final Parcelable.Creator<SavedState> CREATOR =
                new Parcelable.Creator<SavedState>() {
                    public SavedState createFromParcel(Parcel in) {
                        return new SavedState(in);
                    }

                    public SavedState[] newArray(int size) {
                        return new SavedState[size];
                    }
                };
    }

    @Override
    public Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();

        SavedState ss = new SavedState(superState);
        ss.mChecked = mSwitch.isChecked();
        ss.mVisible = isShowing();
        return ss;
    }

    @Override
    public void onRestoreInstanceState(Parcelable state) {
        SavedState ss = (SavedState) state;

        super.onRestoreInstanceState(ss.getSuperState());

        mSwitch.setChecked(ss.mChecked);
        setChecked(ss.mChecked);
        setVisibility(ss.mVisible ? View.VISIBLE : View.GONE);
        mSwitch.setOnCheckedChangeListener(ss.mVisible ? this : null);

        requestLayout();
    }

    private int resourceId(Context context, String type, String name) {
        return context.getResources().getIdentifier(name, type, context.getPackageName());
    }
}
