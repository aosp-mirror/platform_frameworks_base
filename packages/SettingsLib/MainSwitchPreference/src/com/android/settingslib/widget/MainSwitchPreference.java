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
import android.util.AttributeSet;
import android.widget.Switch;

import androidx.preference.PreferenceViewHolder;
import androidx.preference.TwoStatePreference;

import java.util.ArrayList;
import java.util.List;

/**
 * MainSwitchPreference is a Preference with a customized Switch.
 * This component is used as the main switch of the page
 * to enable or disable the prefereces on the page.
 */
public class MainSwitchPreference extends TwoStatePreference implements OnMainSwitchChangeListener {

    private final List<OnMainSwitchChangeListener> mSwitchChangeListeners = new ArrayList<>();

    private MainSwitchBar mMainSwitchBar;
    private CharSequence mTitle;

    public MainSwitchPreference(Context context) {
        super(context);
        init(context, null);
    }

    public MainSwitchPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context, attrs);
    }

    public MainSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    public MainSwitchPreference(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context, attrs);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        holder.setDividerAllowedAbove(false);
        holder.setDividerAllowedBelow(false);

        mMainSwitchBar = (MainSwitchBar) holder.findViewById(R.id.settingslib_main_switch_bar);
        updateStatus(isChecked());
        registerListenerToSwitchBar();
    }

    private void init(Context context, AttributeSet attrs) {
        setLayoutResource(R.layout.settingslib_main_switch_layout);
        mSwitchChangeListeners.add(this);
        if (attrs != null) {
            final TypedArray a = context.obtainStyledAttributes(attrs,
                    androidx.preference.R.styleable.Preference, 0 /*defStyleAttr*/,
                    0 /*defStyleRes*/);
            final CharSequence title = a.getText(
                    androidx.preference.R.styleable.Preference_android_title);
            setTitle(title);
            a.recycle();
        }
    }

    @Override
    public void setChecked(boolean checked) {
        super.setChecked(checked);
        if (mMainSwitchBar != null && mMainSwitchBar.isChecked() != checked) {
            mMainSwitchBar.setChecked(checked);
        }
    }

    @Override
    public void setTitle(CharSequence title) {
        mTitle = title;
        if (mMainSwitchBar != null) {
            mMainSwitchBar.setTitle(mTitle);
        }
    }

    @Override
    public void onSwitchChanged(Switch switchView, boolean isChecked) {
        super.setChecked(isChecked);
    }

    /**
     * Update the switch status of preference
     */
    public void updateStatus(boolean checked) {
        setChecked(checked);
        if (mMainSwitchBar != null) {
            mMainSwitchBar.setTitle(mTitle);
            mMainSwitchBar.show();
        }
    }

    /**
     * Adds a listener for switch changes
     */
    public void addOnSwitchChangeListener(OnMainSwitchChangeListener listener) {
        if (!mSwitchChangeListeners.contains(listener)) {
            mSwitchChangeListeners.add(listener);
        }
        if (mMainSwitchBar != null) {
            mMainSwitchBar.addOnSwitchChangeListener(listener);
        }
    }

    /**
     * Remove a listener for switch changes
     */
    public void removeOnSwitchChangeListener(OnMainSwitchChangeListener listener) {
        mSwitchChangeListeners.remove(listener);
        if (mMainSwitchBar != null) {
            mMainSwitchBar.removeOnSwitchChangeListener(listener);
        }
    }

    private void registerListenerToSwitchBar() {
        for (OnMainSwitchChangeListener listener : mSwitchChangeListeners) {
            mMainSwitchBar.addOnSwitchChangeListener(listener);
        }
    }
}
