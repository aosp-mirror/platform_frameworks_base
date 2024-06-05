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
import android.os.Build;
import android.os.Build.VERSION_CODES;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.ColorInt;

import com.android.settingslib.widget.mainswitch.R;

import java.util.ArrayList;
import java.util.List;

/**
 * MainSwitchBar is a View with a customized Switch.
 * This component is used as the main switch of the page
 * to enable or disable the prefereces on the page.
 */
public class MainSwitchBar extends LinearLayout implements OnCheckedChangeListener {

    private final List<OnCheckedChangeListener> mSwitchChangeListeners = new ArrayList<>();

    @ColorInt
    private int mBackgroundColor;
    @ColorInt
    private int mBackgroundActivatedColor;

    protected TextView mTextView;
    protected CompoundButton mSwitch;
    private final View mFrameView;

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

        LayoutInflater.from(context).inflate(R.layout.settingslib_main_switch_bar, this);

        if (Build.VERSION.SDK_INT < VERSION_CODES.S) {
            TypedArray a;
            if (Build.VERSION.SDK_INT >= VERSION_CODES.M) {
                a = context.obtainStyledAttributes(
                        new int[]{android.R.attr.colorAccent});
                mBackgroundActivatedColor = a.getColor(0, 0);
                mBackgroundColor = context.getColor(androidx.appcompat.R.color.material_grey_600);
            } else {
                a = context.obtainStyledAttributes(new int[]{android.R.attr.colorPrimary});
                mBackgroundActivatedColor = a.getColor(0, 0);
                mBackgroundColor = a.getColor(0, 0);
            }
            a.recycle();
        }

        setFocusable(true);
        setClickable(true);

        mFrameView = findViewById(R.id.frame);
        mTextView = findViewById(R.id.switch_text);
        mSwitch = findViewById(android.R.id.switch_widget);
        addOnSwitchChangeListener((switchView, isChecked) -> setChecked(isChecked));

        if (mSwitch.getVisibility() == VISIBLE) {
            mSwitch.setOnCheckedChangeListener(this);
        }

        setChecked(mSwitch.isChecked());

        if (attrs != null) {
            final TypedArray a = context.obtainStyledAttributes(attrs,
                    androidx.preference.R.styleable.Preference, 0 /*defStyleAttr*/,
                    0 /*defStyleRes*/);
            final CharSequence title = a.getText(
                    androidx.preference.R.styleable.Preference_android_title);
            setTitle(title);
            a.recycle();
        }

        setBackground(mSwitch.isChecked());
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        propagateChecked(isChecked);
    }

    @Override
    public boolean performClick() {
        mSwitch.performClick();
        return super.performClick();
    }

    /**
     * Update the switch status
     */
    public void setChecked(boolean checked) {
        if (mSwitch != null) {
            mSwitch.setChecked(checked);
        }
        setBackground(checked);
    }

    /**
     * Return the status of the Switch
     */
    public boolean isChecked() {
        return mSwitch.isChecked();
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
     * Set icon space reserved for title
     */
    public void setIconSpaceReserved(boolean iconSpaceReserved) {
        if (mTextView != null && (Build.VERSION.SDK_INT < VERSION_CODES.S)) {
            LayoutParams params = (LayoutParams) mTextView.getLayoutParams();
            int iconSpace = getContext().getResources().getDimensionPixelSize(
                    R.dimen.settingslib_switchbar_subsettings_margin_start);
            params.setMarginStart(iconSpaceReserved ? iconSpace : 0);
            mTextView.setLayoutParams(params);
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
    public void addOnSwitchChangeListener(OnCheckedChangeListener listener) {
        if (!mSwitchChangeListeners.contains(listener)) {
            mSwitchChangeListeners.add(listener);
        }
    }

    /**
     * Remove a listener for switch changes
     */
    public void removeOnSwitchChangeListener(OnCheckedChangeListener listener) {
        mSwitchChangeListeners.remove(listener);
    }

    /**
     * Enable or disable the text and switch.
     */
    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        mTextView.setEnabled(enabled);
        mSwitch.setEnabled(enabled);

        if (Build.VERSION.SDK_INT >= VERSION_CODES.S) {
            mFrameView.setEnabled(enabled);
            mFrameView.setActivated(isChecked());
        }
    }

    private void propagateChecked(boolean isChecked) {
        setBackground(isChecked);

        for (OnCheckedChangeListener changeListener : mSwitchChangeListeners) {
            changeListener.onCheckedChanged(mSwitch, isChecked);
        }
    }

    private void setBackground(boolean isChecked) {
        if (Build.VERSION.SDK_INT < VERSION_CODES.S) {
            setBackgroundColor(isChecked ? mBackgroundActivatedColor : mBackgroundColor);
        } else {
            mFrameView.setActivated(isChecked);
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
                    @Override
                    public SavedState createFromParcel(Parcel in) {
                        return new SavedState(in);
                    }

                    @Override
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
        setBackground(ss.mChecked);
        setVisibility(ss.mVisible ? View.VISIBLE : View.GONE);
        mSwitch.setOnCheckedChangeListener(ss.mVisible ? this : null);

        requestLayout();
    }
}
