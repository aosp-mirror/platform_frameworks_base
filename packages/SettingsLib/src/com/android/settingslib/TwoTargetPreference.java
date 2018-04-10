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

import android.annotation.IntDef;
import android.content.Context;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceViewHolder;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

public class TwoTargetPreference extends Preference {

    @IntDef({ICON_SIZE_DEFAULT, ICON_SIZE_MEDIUM, ICON_SIZE_SMALL})
    @Retention(RetentionPolicy.SOURCE)
    public @interface IconSize {
    }

    public static final int ICON_SIZE_DEFAULT = 0;
    public static final int ICON_SIZE_MEDIUM = 1;
    public static final int ICON_SIZE_SMALL = 2;

    @IconSize
    private int mIconSize;
    private int mSmallIconSize;
    private int mMediumIconSize;

    public TwoTargetPreference(Context context, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init(context);
    }

    public TwoTargetPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    public TwoTargetPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public TwoTargetPreference(Context context) {
        super(context);
        init(context);
    }

    private void init(Context context) {
        setLayoutResource(R.layout.preference_two_target);
        mSmallIconSize = context.getResources().getDimensionPixelSize(
                R.dimen.two_target_pref_small_icon_size);
        mMediumIconSize = context.getResources().getDimensionPixelSize(
                R.dimen.two_target_pref_medium_icon_size);
        final int secondTargetResId = getSecondTargetResId();
        if (secondTargetResId != 0) {
            setWidgetLayoutResource(secondTargetResId);
        }
    }

    public void setIconSize(@IconSize int iconSize) {
        mIconSize = iconSize;
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        final ImageView icon = holder.itemView.findViewById(android.R.id.icon);
        switch (mIconSize) {
            case ICON_SIZE_SMALL:
                icon.setLayoutParams(new LinearLayout.LayoutParams(mSmallIconSize, mSmallIconSize));
                break;
            case ICON_SIZE_MEDIUM:
                icon.setLayoutParams(
                        new LinearLayout.LayoutParams(mMediumIconSize, mMediumIconSize));
                break;
        }
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
