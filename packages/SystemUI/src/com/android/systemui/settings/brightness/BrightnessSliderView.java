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
import android.content.res.TypedArray;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.FrameLayout;
import android.widget.SeekBar.OnSeekBarChangeListener;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.settingslib.RestrictedLockUtils;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.R;

/**
 * {@code FrameLayout} used to show and manipulate a {@link ToggleSeekBar}.
 *
 * It can additionally control a {@link CompoundButton} and display a label. For the class to work,
 * add children before inflation with the following ids:
 * <ul>
 *     <li>{@code @id/slider} of type {@link ToggleSeekBar}</li>
 *     <li>{@code @id/toggle} of type {@link CompoundButton} (optional)</li>
 *     <li>{@code @id/label} of type {@link TextView} (optional)</li>
 * </ul>
 */
public class BrightnessSliderView extends FrameLayout {

    @Nullable
    private CompoundButton mToggle;
    @NonNull
    private ToggleSeekBar mSlider;
    @Nullable
    private TextView mLabel;
    private final CharSequence mText;
    private DispatchTouchEventListener mListener;
    private Gefingerpoken mOnInterceptListener;

    public BrightnessSliderView(Context context) {
        this(context, null);
    }

    public BrightnessSliderView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BrightnessSliderView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        final TypedArray a = context.obtainStyledAttributes(
                attrs, R.styleable.ToggleSliderView, defStyle, 0);
        mText = a.getString(R.styleable.ToggleSliderView_text);

        a.recycle();
    }

    // Inflated from quick_settings_brightness_dialog or quick_settings_brightness_dialog_thick
    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mToggle = findViewById(R.id.toggle);

        mSlider = requireViewById(R.id.slider);

        mLabel = findViewById(R.id.label);
        if (mLabel != null) {
            mLabel.setText(mText);
        }
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
     * Attaches a listener to the {@link CompoundButton} in the view (if present) so changes to its
     * state can be observed
     * @param checkListener use {@code null} to remove listener
     */
    public void setOnCheckedChangeListener(OnCheckedChangeListener checkListener) {
        if (mToggle != null) {
            mToggle.setOnCheckedChangeListener(checkListener);
        }
    }

    /**
     * Enforces admin rules for toggling auto-brightness and changing value of brightness
     * @param admin
     * @see ToggleSeekBar#setEnforcedAdmin
     */
    public void setEnforcedAdmin(RestrictedLockUtils.EnforcedAdmin admin) {
        if (mToggle != null) {
            mToggle.setEnabled(admin == null);
        }
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
     * Sets the state of the {@link CompoundButton} if present
     * @param checked
     */
    public void setChecked(boolean checked) {
        if (mToggle != null) {
            mToggle.setChecked(checked);
        }
    }

    /**
     * @return the state of the {@link CompoundButton} if present, or {@code true} if not.
     */
    public boolean isChecked() {
        if (mToggle != null) {
            return mToggle.isChecked();
        }
        return true;
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

