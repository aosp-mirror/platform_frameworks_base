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
import android.util.AttributeSet;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.preference.topintro.R;

/**
 * The TopIntroPreference shows a text which describe a feature. Gernerally, we expect this
 * preference always shows on the top of screen.
 */
public class TopIntroPreference extends Preference {

    public TopIntroPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.top_intro_preference);
        setSelectable(false);
    }

    public TopIntroPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.top_intro_preference);
        setSelectable(false);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.setDividerAllowedAbove(false);
        holder.setDividerAllowedBelow(false);
    }
}
