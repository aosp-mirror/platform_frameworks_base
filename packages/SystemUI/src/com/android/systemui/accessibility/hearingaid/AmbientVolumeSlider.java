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

package com.android.systemui.accessibility.hearingaid;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.res.R;

import com.google.android.material.slider.Slider;

import java.util.ArrayList;
import java.util.List;

/**
 * A view of ambient volume slider.
 * <p> It consists by a title {@link TextView} with a volume control {@link Slider}.
 */
public class AmbientVolumeSlider extends LinearLayout {

    private final TextView mTitle;
    private final Slider mSlider;
    private final List<OnChangeListener> mChangeListeners = new ArrayList<>();
    private final Slider.OnSliderTouchListener mSliderTouchListener =
            new Slider.OnSliderTouchListener() {
                @Override
                public void onStartTrackingTouch(@NonNull Slider slider) {
                    mTrackingTouch = true;
                }

                @Override
                public void onStopTrackingTouch(@NonNull Slider slider) {
                    mTrackingTouch = false;
                    final int value = Math.round(slider.getValue());
                    for (OnChangeListener listener : mChangeListeners) {
                        listener.onValueChange(AmbientVolumeSlider.this, value);
                    }
                }
            };
    private final Slider.OnChangeListener mSliderChangeListener = new Slider.OnChangeListener() {
        @Override
        public void onValueChange(@NonNull Slider slider, float value, boolean fromUser) {
            if (fromUser && !mTrackingTouch) {
                final int roundedValue = Math.round(value);
                for (OnChangeListener listener : mChangeListeners) {
                    listener.onValueChange(AmbientVolumeSlider.this, roundedValue);
                }
            }
        }
    };
    private boolean mTrackingTouch = false;

    public AmbientVolumeSlider(@Nullable Context context) {
        this(context, /* attrs= */ null);
    }

    public AmbientVolumeSlider(@Nullable Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, /* defStyleAttr= */ 0);
    }

    public AmbientVolumeSlider(@Nullable Context context, @Nullable AttributeSet attrs,
            int defStyleAttr) {
        this(context, attrs, defStyleAttr, /* defStyleRes= */ 0);
    }

    public AmbientVolumeSlider(@Nullable Context context, @Nullable AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);

        inflate(context, R.layout.hearing_device_ambient_volume_slider, /* root= */ this);
        mTitle = requireViewById(R.id.ambient_volume_slider_title);
        mSlider = requireViewById(R.id.ambient_volume_slider);
        mSlider.addOnSliderTouchListener(mSliderTouchListener);
        mSlider.addOnChangeListener(mSliderChangeListener);
    }

    /**
     * Sets title for the ambient volume slider.
     * <p> If text is null or empty, then {@link TextView} is hidden.
     */
    public void setTitle(@Nullable String text) {
        mTitle.setText(text);
        mTitle.setVisibility(TextUtils.isEmpty(text) ? GONE : VISIBLE);
    }

    /** Gets title for the ambient volume slider. */
    public CharSequence getTitle() {
        return mTitle.getText();
    }

    /**
     * Adds the callback to the ambient volume slider to get notified when the value is changed by
     * user.
     * <p> Note: The {@link OnChangeListener#onValueChange(AmbientVolumeSlider, int)} will be
     * called when user's finger take off from the slider.
     */
    public void addOnChangeListener(@Nullable OnChangeListener listener) {
        if (listener == null) {
            return;
        }
        mChangeListeners.add(listener);
    }

    /** Sets max value to the ambient volume slider. */
    public void setMax(float max) {
        mSlider.setValueTo(max);
    }

    /** Gets max value from the ambient volume slider. */
    public float getMax() {
        return mSlider.getValueTo();
    }

    /** Sets min value to the ambient volume slider. */
    public void setMin(float min) {
        mSlider.setValueFrom(min);
    }

    /** Gets min value from the ambient volume slider. */
    public float getMin() {
        return mSlider.getValueFrom();
    }

    /** Sets value to the ambient volume slider. */
    public void setValue(float value) {
        mSlider.setValue(value);
    }

    /** Gets value from the ambient volume slider. */
    public float getValue() {
        return mSlider.getValue();
    }

    /** Sets the enable state to the ambient volume slider. */
    public void setEnabled(boolean enabled) {
        mSlider.setEnabled(enabled);
    }

    /** Gets the enable state of the ambient volume slider. */
    public boolean isEnabled() {
        return mSlider.isEnabled();
    }

    /**
     * Gets the volume value of the ambient volume slider.
     * <p> The volume level is divided into 5 levels:
     * Level 0 corresponds to the minimum volume value. The range between the minimum and maximum
     * volume is divided into 4 equal intervals, represented by levels 1 to 4.
     */
    public int getVolumeLevel() {
        if (!mSlider.isEnabled()) {
            return 0;
        }
        final double min = mSlider.getValueFrom();
        final double max = mSlider.getValueTo();
        final double levelGap = (max - min) / 4.0;
        final double value = mSlider.getValue();
        return (int) Math.ceil((value - min) / levelGap);
    }

    /** Interface definition for a callback invoked when a slider's value is changed. */
    public interface OnChangeListener {
        /** Called when the finger is take off from the slider. */
        void onValueChange(@NonNull AmbientVolumeSlider slider, int value);
    }
}
