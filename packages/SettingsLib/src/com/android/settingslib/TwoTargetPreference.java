/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.content.Context;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceViewHolder;
import android.util.AttributeSet;
import android.view.View;

public class TwoTargetPreference extends Preference {

    public TwoTargetPreference(Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    public TwoTargetPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public TwoTargetPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TwoTargetPreference(Context context) {
        super(context);
        init();
    }

    private void init() {
        setLayoutResource(R.layout.preference_two_target);
        final int secondTargetResId = getSecondTargetResId();
        if (secondTargetResId != 0) {
            setWidgetLayoutResource(secondTargetResId);
        }
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final View divider = holder.findViewById(R.id.two_target_divider);
        final View widgetFrame = holder.findViewById(android.R.id.widget_frame);
        final boolean shouldHideSecondTarget = shouldHideSecondTarget();
        if (divider != null) {
            divider.setVisibility(shouldHideSecondTarget ? View.GONE : View.VISIBLE);
        }
        if (widgetFrame != null) {
            widgetFrame.setVisibility(shouldHideSecondTarget ? View.GONE : View.VISIBLE);
        }
    }

    protected boolean shouldHideSecondTarget() {
        return getSecondTargetResId() == 0;
    }

    protected int getSecondTargetResId() {
        return 0;
    }
}
