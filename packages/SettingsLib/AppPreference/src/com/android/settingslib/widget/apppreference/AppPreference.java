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

package com.android.settingslib.widget.apppreference;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ProgressBar;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.R;

public class AppPreference extends Preference {

    private int mProgress;
    private boolean mProgressVisible;

    public AppPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.preference_app);
    }

    public AppPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.preference_app);
    }

    public void setProgress(int amount) {
        mProgress = amount;
        mProgressVisible = true;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder view) {
        super.onBindViewHolder(view);

        view.findViewById(R.id.summary_container)
                .setVisibility(TextUtils.isEmpty(getSummary()) ? View.GONE : View.VISIBLE);
        final ProgressBar progress = (ProgressBar) view.findViewById(android.R.id.progress);
        if (mProgressVisible) {
            progress.setProgress(mProgress);
            progress.setVisibility(View.VISIBLE);
        } else {
            progress.setVisibility(View.GONE);
        }
    }
}