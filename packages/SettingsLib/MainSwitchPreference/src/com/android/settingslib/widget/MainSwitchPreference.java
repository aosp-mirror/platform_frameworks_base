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
import android.os.Build;
import android.util.AttributeSet;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.TwoStatePreference;

import com.android.settingslib.widget.mainswitch.R;

import java.util.ArrayList;
import java.util.List;

/**
 * MainSwitchPreference is a Preference with a customized Switch.
 * This component is used as the main switch of the page
 * to enable or disable the preferences on the page.
 */
public class MainSwitchPreference extends TwoStatePreference implements OnCheckedChangeListener,
        GroupSectionDividerMixin {

    private final List<OnCheckedChangeListener> mSwitchChangeListeners = new ArrayList<>();

    public MainSwitchPreference(@NonNull Context context) {
        this(context, null);
    }

    public MainSwitchPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public MainSwitchPreference(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public MainSwitchPreference(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        boolean isExpressive = SettingsThemeHelper.isExpressiveTheme(context);
        int resId = isExpressive ? R.layout.settingslib_expressive_main_switch_layout
                : R.layout.settingslib_main_switch_layout;
        setLayoutResource(resId);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        holder.setDividerAllowedAbove(false);
        holder.setDividerAllowedBelow(false);

        MainSwitchBar mainSwitchBar = holder.itemView.requireViewById(
                R.id.settingslib_main_switch_bar);
        mainSwitchBar.setTitle(getTitle());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            mainSwitchBar.setSummary(getSummary());
        }
        mainSwitchBar.setIconSpaceReserved(isIconSpaceReserved());
        // To support onPreferenceChange callback, it needs to call callChangeListener() when
        // MainSwitchBar is clicked.
        mainSwitchBar.setOnClickListener(view -> callChangeListener(isChecked()));

        // Remove all listeners to 1. avoid triggering listener when update UI 2. prevent potential
        // listener leaking when the view holder is reused by RecyclerView
        mainSwitchBar.removeAllOnSwitchChangeListeners();
        mainSwitchBar.setChecked(isChecked());
        mainSwitchBar.addOnSwitchChangeListener(this);

        mainSwitchBar.show();
    }

    @Override
    public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        super.setChecked(isChecked);
        for (OnCheckedChangeListener listener : mSwitchChangeListeners) {
            listener.onCheckedChanged(buttonView, isChecked);
        }
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
}
