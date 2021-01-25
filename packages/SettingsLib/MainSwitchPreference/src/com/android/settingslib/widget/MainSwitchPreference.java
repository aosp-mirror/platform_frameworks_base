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
import android.text.TextUtils;
import android.util.AttributeSet;

import androidx.core.content.res.TypedArrayUtils;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.TwoStatePreference;

import com.android.settingslib.RestrictedLockUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * MainSwitchPreference is a Preference with a customized Switch.
 * This component is used as the main switch of the page
 * to enable or disable the prefereces on the page.
 */
public class MainSwitchPreference extends TwoStatePreference {

    private final List<OnMainSwitchChangeListener> mSwitchChangeListeners = new ArrayList<>();

    private MainSwitchBar mMainSwitchBar;
    private String mTitle;

    private RestrictedLockUtils.EnforcedAdmin mEnforcedAdmin;

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

        holder.setDividerAllowedAbove(true);
        holder.setDividerAllowedBelow(false);

        mMainSwitchBar = (MainSwitchBar) holder.findViewById(R.id.main_switch_bar);
        updateStatus(isChecked());
        registerListenerToSwitchBar();
    }

    private void init(Context context, AttributeSet attrs) {
        setLayoutResource(R.layout.main_switch_layout);

        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs,
                    androidx.preference.R.styleable.Preference, 0 /*defStyleAttr*/,
                    0 /*defStyleRes*/);
            final CharSequence title = TypedArrayUtils.getText(a,
                    androidx.preference.R.styleable.Preference_title,
                    androidx.preference.R.styleable.Preference_android_title);
            if (!TextUtils.isEmpty(title)) {
                setTitle(title.toString());
            }
            a.recycle();
        }
    }

    /**
     * Set the preference title text
     */
    public void setTitle(String text) {
        mTitle = text;
        if (mMainSwitchBar != null) {
            mMainSwitchBar.setTitle(mTitle);
        }
    }

    /**
     * Update the switch status of preference
     */
    public void updateStatus(boolean checked) {
        setChecked(checked);
        if (mMainSwitchBar != null) {
            mMainSwitchBar.setChecked(checked);
            mMainSwitchBar.setTitle(mTitle);
            mMainSwitchBar.setDisabledByAdmin(mEnforcedAdmin);
            mMainSwitchBar.show();
        }
    }

    /**
     * Adds a listener for switch changes
     */
    public void addOnSwitchChangeListener(OnMainSwitchChangeListener listener) {
        if (mMainSwitchBar == null) {
            mSwitchChangeListeners.add(listener);
        } else {
            mMainSwitchBar.addOnSwitchChangeListener(listener);
        }
    }

    /**
     * Remove a listener for switch changes
     */
    public void removeOnSwitchChangeListener(OnMainSwitchChangeListener listener) {
        if (mMainSwitchBar == null) {
            mSwitchChangeListeners.remove(listener);
        } else {
            mMainSwitchBar.removeOnSwitchChangeListener(listener);
        }
    }

    /**
     * If admin is not null, disables the text and switch but keeps the view clickable.
     * Otherwise, calls setEnabled which will enables the entire view including
     * the text and switch.
     */
    public void setDisabledByAdmin(RestrictedLockUtils.EnforcedAdmin admin) {
        mEnforcedAdmin = admin;
        if (mMainSwitchBar != null) {
            mMainSwitchBar.setDisabledByAdmin(mEnforcedAdmin);
        }
    }

    private void registerListenerToSwitchBar() {
        for (OnMainSwitchChangeListener listener : mSwitchChangeListeners) {
            mMainSwitchBar.addOnSwitchChangeListener(listener);
        }
        mSwitchChangeListeners.clear();
    }
}
