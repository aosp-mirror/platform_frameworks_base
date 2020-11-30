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

package com.android.settingslib.widget;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.preference.PreferenceViewHolder;

/** A slider preference with left and right labels **/
public class LabeledSeekBarPreference extends SeekBarPreference {

    private final int mTextStartId;
    private final int mTextEndId;
    private final int mTickMarkId;
    private OnPreferenceChangeListener mStopListener;

    public LabeledSeekBarPreference(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {

        super(context, attrs, defStyleAttr, defStyleRes);
        setLayoutResource(R.layout.preference_labeled_slider);

        final TypedArray styledAttrs = context.obtainStyledAttributes(attrs,
                R.styleable.LabeledSeekBarPreference);
        mTextStartId = styledAttrs.getResourceId(
                R.styleable.LabeledSeekBarPreference_textStart,
                R.string.summary_placeholder);
        mTextEndId = styledAttrs.getResourceId(
                R.styleable.LabeledSeekBarPreference_textEnd,
                R.string.summary_placeholder);
        mTickMarkId = styledAttrs.getResourceId(
                R.styleable.LabeledSeekBarPreference_tickMark, /* defValue= */ 0);
        styledAttrs.recycle();
    }

    public LabeledSeekBarPreference(Context context, AttributeSet attrs) {
        this(context, attrs, com.android.internal.R.attr.seekBarPreferenceStyle, 0);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);

        final TextView startText = (TextView) holder.findViewById(android.R.id.text1);
        final TextView endText = (TextView) holder.findViewById(android.R.id.text2);
        startText.setText(mTextStartId);
        endText.setText(mTextEndId);

        if (mTickMarkId != 0) {
            final Drawable tickMark = getContext().getDrawable(mTickMarkId);
            final SeekBar seekBar = (SeekBar) holder.findViewById(
                    com.android.internal.R.id.seekbar);
            seekBar.setTickMark(tickMark);
        }
    }

    public void setOnPreferenceChangeStopListener(OnPreferenceChangeListener listener) {
        mStopListener = listener;
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        super.onStopTrackingTouch(seekBar);

        if (mStopListener != null) {
            mStopListener.onPreferenceChange(this, seekBar.getProgress());
        }
    }
}

