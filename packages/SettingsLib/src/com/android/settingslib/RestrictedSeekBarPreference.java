/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.util.AttributeSet;
import android.view.View;

import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.DefaultIndicatorSeekBar;
import com.android.settingslib.widget.SeekBarPreference;

/**
 * Based on android.preference.SeekBarPreference, but uses support preference as base.
 */
public class RestrictedSeekBarPreference extends SeekBarPreference implements Restrictable {

    private CharSequence mSeekBarStateDescription;
    private RestrictedPreferenceHelper mHelper;

    public RestrictedSeekBarPreference(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        mHelper = new RestrictedPreferenceHelper(context, this, attrs);

        final int secondTargetResId = getSecondTargetResId();
        if (secondTargetResId != 0) {
            setWidgetLayoutResource(secondTargetResId);
        }
    }

    public RestrictedSeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public RestrictedSeekBarPreference(Context context, AttributeSet attrs) {
        this(context, attrs, androidx.preference.R.attr.seekBarPreferenceStyle);
    }

    public RestrictedSeekBarPreference(Context context) {
        this(context, null);
    }

    @Override
    public boolean isSelectable() {
        if (isDisabledByAdmin()) {
            return true;
        } else {
            return super.isSelectable();
        }
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        if (mSeekBar instanceof DefaultIndicatorSeekBar) {
            ((DefaultIndicatorSeekBar) mSeekBar).setDefaultProgress(mDefaultProgress);
        }

        mHelper.onBindViewHolder(holder);
        final View restrictedIcon = holder.findViewById(R.id.restricted_icon);
        if (restrictedIcon != null) {
            restrictedIcon.setVisibility(isDisabledByAdmin() ? View.VISIBLE : View.GONE);
        }

        final View widgetFrame = holder.findViewById(android.R.id.widget_frame);
        if (widgetFrame != null) {
            widgetFrame.setVisibility(shouldHideSecondTarget() ? View.GONE : View.VISIBLE);
        }
    }

    @Override
    public void performClick() {
        if (!mHelper.performClick()) {
            super.performClick();
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (enabled && isDisabledByAdmin()) {
            mHelper.setDisabledByAdmin(null);
            return;
        }
        super.setEnabled(enabled);
    }

    /**
     * Sets the progress point to draw a single tick mark representing a default value.
     */
    public void setDefaultProgress(int defaultProgress) {
        if (mDefaultProgress != defaultProgress) {
            mDefaultProgress = defaultProgress;
            if (mSeekBar instanceof DefaultIndicatorSeekBar) {
                ((DefaultIndicatorSeekBar) mSeekBar).setDefaultProgress(mDefaultProgress);
            }
        }
    }

    @Override
    public RestrictedPreferenceHelper getHelper() {
        return mHelper;
    }

    @Override
    public void notifyPreferenceChanged() {
        notifyChanged();
    }

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        mHelper.onAttachedToHierarchy();
        super.onAttachedToHierarchy(preferenceManager);
    }

    protected int getSecondTargetResId() {
        return R.layout.restricted_icon;
    }

    protected boolean shouldHideSecondTarget() {
        return !isDisabledByAdmin();
    }
}
