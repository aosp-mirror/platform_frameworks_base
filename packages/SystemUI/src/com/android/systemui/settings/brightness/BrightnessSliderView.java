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

package com.android.systemui.settings.brightness;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.SeekBar.OnSeekBarChangeListener;

import androidx.annotation.NonNull;

import com.android.settingslib.RestrictedLockUtils;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.R;

/**
 * {@code FrameLayout} used to show and manipulate a {@link ToggleSeekBar}.
 *
 */
public class BrightnessSliderView extends FrameLayout {

    @NonNull
    private ToggleSeekBar mSlider;
    private DispatchTouchEventListener mListener;
    private Gefingerpoken mOnInterceptListener;

    public BrightnessSliderView(Context context) {
        this(context, null);
    }

    public BrightnessSliderView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    // Inflated from quick_settings_brightness_dialog or quick_settings_brightness_dialog_thick
    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mSlider = requireViewById(R.id.slider);
        mSlider.setAccessibilityLabel(getContentDescription().toString());
    }

    /**
     * Attaches a listener to relay touch events.
     * @param listener use {@code null} to remove listener
     */
    public void setOnDispatchTouchEventListener(
            DispatchTouchEventListener listener) {
        mListener = listener;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (mListener != null) {
            mListener.onDispatchTouchEvent(ev);
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        // We prevent disallowing on this view, but bubble it up to our parents.
        // We need interception to handle falsing.
        if (mParent != null) {
            mParent.requestDisallowInterceptTouchEvent(disallowIntercept);
        }
    }

    /**
     * Attaches a listener to the {@link ToggleSeekBar} in the view so changes can be observed
     * @param seekListener use {@code null} to remove listener
     */
    public void setOnSeekBarChangeListener(OnSeekBarChangeListener seekListener) {
        mSlider.setOnSeekBarChangeListener(seekListener);
    }

    /**
     * Enforces admin rules for toggling auto-brightness and changing value of brightness
     * @param admin
     * @see ToggleSeekBar#setEnforcedAdmin
     */
    public void setEnforcedAdmin(RestrictedLockUtils.EnforcedAdmin admin) {
        mSlider.setEnabled(admin == null);
        mSlider.setEnforcedAdmin(admin);
    }

    /**
     * Enables or disables the slider
     * @param enable
     */
    public void enableSlider(boolean enable) {
        mSlider.setEnabled(enable);
    }

    /**
     * @return the maximum value of the {@link ToggleSeekBar}.
     */
    public int getMax() {
        return mSlider.getMax();
    }

    /**
     * Sets the maximum value of the {@link ToggleSeekBar}.
     * @param max
     */
    public void setMax(int max) {
        mSlider.setMax(max);
    }

    /**
     * Sets the current value of the {@link ToggleSeekBar}.
     * @param value
     */
    public void setValue(int value) {
        mSlider.setProgress(value);
    }

    /**
     * @return the current value of the {@link ToggleSeekBar}
     */
    public int getValue() {
        return mSlider.getProgress();
    }

    public void setOnInterceptListener(Gefingerpoken onInterceptListener) {
        mOnInterceptListener = onInterceptListener;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (mOnInterceptListener != null) {
            return mOnInterceptListener.onInterceptTouchEvent(ev);
        }
        return super.onInterceptTouchEvent(ev);
    }

    /**
     * Interface to attach a listener for {@link View#dispatchTouchEvent}.
     */
    @FunctionalInterface
    interface DispatchTouchEventListener {
        boolean onDispatchTouchEvent(MotionEvent ev);
    }
}

