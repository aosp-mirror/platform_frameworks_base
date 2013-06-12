/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.systemui.settings;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import com.android.systemui.R;

public class ToggleSlider extends RelativeLayout
        implements CompoundButton.OnCheckedChangeListener, SeekBar.OnSeekBarChangeListener {
    private static final String TAG = "StatusBar.ToggleSlider";

    public interface Listener {
        public void onInit(ToggleSlider v);
        public void onChanged(ToggleSlider v, boolean tracking, boolean checked, int value);
    }

    private Listener mListener;
    private boolean mTracking;

    private CompoundButton mToggle;
    private SeekBar mSlider;
    private TextView mLabel;

    public ToggleSlider(Context context) {
        this(context, null);
    }

    public ToggleSlider(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ToggleSlider(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        View.inflate(context, R.layout.status_bar_toggle_slider, this);

        final Resources res = context.getResources();
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.ToggleSlider,
                defStyle, 0);

        mToggle = (CompoundButton)findViewById(R.id.toggle);
        mToggle.setOnCheckedChangeListener(this);
        mToggle.setBackgroundDrawable(res.getDrawable(R.drawable.status_bar_toggle_button));

        mSlider = (SeekBar)findViewById(R.id.slider);
        mSlider.setOnSeekBarChangeListener(this);

        mLabel = (TextView)findViewById(R.id.label);
        mLabel.setText(a.getString(R.styleable.ToggleSlider_text));

        a.recycle();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mListener != null) {
            mListener.onInit(this);
        }
    }

    public void onCheckedChanged(CompoundButton toggle, boolean checked) {
        Drawable thumb;
        Drawable slider;
        final Resources res = getContext().getResources();
        if (checked) {
            thumb = res.getDrawable(
                    com.android.internal.R.drawable.scrubber_control_disabled_holo);
            slider = res.getDrawable(
                    R.drawable.status_bar_settings_slider_disabled);
        } else {
            thumb = res.getDrawable(
                    com.android.internal.R.drawable.scrubber_control_selector_holo);
            slider = res.getDrawable(
                    com.android.internal.R.drawable.scrubber_progress_horizontal_holo_dark);
        }
        mSlider.setThumb(thumb);
        mSlider.setProgressDrawable(slider);

        if (mListener != null) {
            mListener.onChanged(this, mTracking, checked, mSlider.getProgress());
        }
    }

    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (mListener != null) {
            mListener.onChanged(this, mTracking, mToggle.isChecked(), progress);
        }
    }

    public void onStartTrackingTouch(SeekBar seekBar) {
        mTracking = true;
        if (mListener != null) {
            mListener.onChanged(this, mTracking, mToggle.isChecked(), mSlider.getProgress());
        }
        mToggle.setChecked(false);
    }

    public void onStopTrackingTouch(SeekBar seekBar) {
        mTracking = false;
        if (mListener != null) {
            mListener.onChanged(this, mTracking, mToggle.isChecked(), mSlider.getProgress());
        }
    }

    public void setOnChangedListener(Listener l) {
        mListener = l;
    }

    public void setChecked(boolean checked) {
        mToggle.setChecked(checked);
    }

    public boolean isChecked() {
        return mToggle.isChecked();
    }

    public void setMax(int max) {
        mSlider.setMax(max);
    }

    public void setValue(int value) {
        mSlider.setProgress(value);
    }
}

