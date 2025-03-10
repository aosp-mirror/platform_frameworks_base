/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.settingslib;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Process;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.TypedArrayUtils;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.SliderPreference;

/**
 * Slide Preference that supports being disabled by a user restriction
 * set by a device admin.
 */
public class RestrictedSliderPreference extends SliderPreference implements
        RestrictedPreferenceHelperProvider {

    private final RestrictedPreferenceHelper mHelper;

    public RestrictedSliderPreference(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyleAttr, @Nullable String packageName, int uid) {
        super(context, attrs, defStyleAttr);
        mHelper = new RestrictedPreferenceHelper(context, this, attrs, packageName, uid);
    }

    public RestrictedSliderPreference(@NonNull Context context,
            @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, null, Process.INVALID_UID);
    }

    @SuppressLint("RestrictedApi")
    public RestrictedSliderPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, TypedArrayUtils.getAttr(context, R.attr.preferenceStyle,
                android.R.attr.preferenceStyle));
    }

    public RestrictedSliderPreference(@NonNull Context context) {
        this(context, null);
    }

    @SuppressLint("RestrictedApi")
    public RestrictedSliderPreference(@NonNull Context context, @Nullable String packageName,
            int uid) {
        this(context, null, TypedArrayUtils.getAttr(context, R.attr.preferenceStyle,
                android.R.attr.preferenceStyle), packageName, uid);
    }

    @Override
    public @NonNull RestrictedPreferenceHelper getRestrictedPreferenceHelper() {
        return mHelper;
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        mHelper.onBindViewHolder(holder);
    }

    @SuppressLint("RestrictedApi")
    @Override
    public void performClick() {
        if (!mHelper.performClick()) {
            super.performClick();
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (enabled && mHelper.isDisabledByAdmin()) {
            mHelper.setDisabledByAdmin(null);
            return;
        }

        if (enabled && mHelper.isDisabledByEcm()) {
            mHelper.setDisabledByEcm(null);
            return;
        }

        super.setEnabled(enabled);
    }

    @Override
    protected void onAttachedToHierarchy(@NonNull PreferenceManager preferenceManager) {
        mHelper.onAttachedToHierarchy();
        super.onAttachedToHierarchy(preferenceManager);
    }
}
