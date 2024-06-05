/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.preference.app.R;

/**
 * The Preference for the pages need to show big apps icon and name in the header of the page.
 */
public class AppHeaderPreference extends Preference {

    private boolean mIsInstantApp;
    private CharSequence mSecondSummary;

    public AppHeaderPreference(Context context, AttributeSet attrs, int defStyleAttr,
                               int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public AppHeaderPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public AppHeaderPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public AppHeaderPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setLayoutResource(R.layout.app_header_preference);
        setSelectable(false);
        setIsInstantApp(false);
    }

    /**
     * Returns the installation type of this preference.
     *
     * @return whether the app is an instant app
     * @see #setIsInstantApp(boolean)
     */
    public boolean getIsInstantApp() {
        return mIsInstantApp;
    }

    /**
     * Sets the installation type for this preference with a boolean.
     *
     * @param isInstantApp whether the app is an instant app
     */
    public void setIsInstantApp(@NonNull boolean isInstantApp) {
        if (mIsInstantApp != isInstantApp) {
            mIsInstantApp = isInstantApp;
            notifyChanged();
        }
    }

    /**
     * Returns the second summary of this preference.
     *
     * @return The second summary
     * @see #setSecondSummary(CharSequence)
     */
    @Nullable
    public CharSequence getSecondSummary() {
        return mSecondSummary;
    }

    /**
     * Sets the second summary for this preference with a CharSequence.
     *
     * @param secondSummary The second summary for the preference
     */
    public void setSecondSummary(@Nullable CharSequence secondSummary) {
        if (!TextUtils.equals(mSecondSummary, secondSummary)) {
            mSecondSummary = secondSummary;
            notifyChanged();
        }
    }

    /**
     * Sets the second summary for this preference with a resource ID.
     *
     * @param secondSummaryResId The second summary as a resource
     * @see #setSecondSummary(CharSequence)
     */
    public void setSecondSummary(@StringRes int secondSummaryResId) {
        setSecondSummary(getContext().getString(secondSummaryResId));
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        final TextView installTypeView = (TextView) holder.findViewById(R.id.install_type);
        if (installTypeView != null) {
            if (mIsInstantApp) {
                installTypeView.setVisibility(View.VISIBLE);
            } else {
                installTypeView.setVisibility(View.GONE);
            }
        }

        final TextView secondSummaryView = (TextView) holder.findViewById(R.id.second_summary);
        if (secondSummaryView != null) {
            if (!TextUtils.isEmpty(mSecondSummary)) {
                secondSummaryView.setText(mSecondSummary);
                secondSummaryView.setVisibility(View.VISIBLE);
            } else {
                secondSummaryView.setVisibility(View.GONE);
            }
        }
    }
}
