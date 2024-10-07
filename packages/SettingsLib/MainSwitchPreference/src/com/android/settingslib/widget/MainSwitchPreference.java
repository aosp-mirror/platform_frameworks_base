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
import android.util.AttributeSet;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;

import androidx.preference.PreferenceViewHolder;
import androidx.preference.TwoStatePreference;

import com.android.settingslib.widget.mainswitch.R;

import java.util.ArrayList;
import java.util.List;

/**
 * MainSwitchPreference is a Preference with a customized Switch.
 * This component is used as the main switch of the page
 * to enable or disable the prefereces on the page.
 */
public class MainSwitchPreference extends TwoStatePreference implements OnCheckedChangeListener {

    private final List<OnCheckedChangeListener> mSwitchChangeListeners = new ArrayList<>();

    private MainSwitchBar mMainSwitchBar;

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
        // To support onPreferenceChange callback, it needs to call callChangeListener() when
        // MainSwitchBar is clicked.
        mMainSwitchBar.setOnClickListener((view) -> callChangeListener(isChecked()));
        setIconSpaceReserved(isIconSpaceReserved());
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

            CharSequence summary = a.getText(
                    androidx.preference.R.styleable.Preference_android_summary);
            setSummary(summary);

            final boolean bIconSpaceReserved = a.getBoolean(
                    androidx.preference.R.styleable.Preference_android_iconSpaceReserved, true);
            setIconSpaceReserved(bIconSpaceReserved);
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
        super.setTitle(title);
        if (mMainSwitchBar != null) {
            mMainSwitchBar.setTitle(title);
        }
    }

    @Override
    public void setSummary(CharSequence summary) {
        super.setSummary(summary);
        if (mMainSwitchBar != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            mMainSwitchBar.setSummary(summary);
        }
    }

    @Override
    public void setIconSpaceReserved(boolean iconSpaceReserved) {
        super.setIconSpaceReserved(iconSpaceReserved);
        if (mMainSwitchBar != null) {
            mMainSwitchBar.setIconSpaceReserved(iconSpaceReserved);
        }
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        super.setChecked(isChecked);
    }

    /**
     * Update the switch status of preference
     */
    public void updateStatus(boolean checked) {
        setChecked(checked);
        if (mMainSwitchBar != null) {
            mMainSwitchBar.setTitle(getTitle());
            mMainSwitchBar.show();
        }
    }

    /**
     * Adds a listener for switch changes
     */
    public void addOnSwitchChangeListener(OnCheckedChangeListener listener) {
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
    public void removeOnSwitchChangeListener(OnCheckedChangeListener listener) {
        mSwitchChangeListeners.remove(listener);
        if (mMainSwitchBar != null) {
            mMainSwitchBar.removeOnSwitchChangeListener(listener);
        }
    }

    private void registerListenerToSwitchBar() {
        for (OnCheckedChangeListener listener : mSwitchChangeListeners) {
            mMainSwitchBar.addOnSwitchChangeListener(listener);
        }
    }
}
