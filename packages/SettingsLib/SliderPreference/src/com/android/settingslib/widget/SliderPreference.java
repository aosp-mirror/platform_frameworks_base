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

package com.android.settingslib.widget;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.preference.slider.R;

import com.google.android.material.slider.LabelFormatter;
import com.google.android.material.slider.Slider;

/**
 * A {@link Preference} that displays a {@link Slider}.
 */
public class SliderPreference extends Preference {
    private static final String TAG = "SliderPreference";

    private final int mTextStartId;
    private final int mTextEndId;
    private final int mIconStartId;
    private final int mIconEndId;
    private final int mIconStartContentDescriptionId;
    private final int mIconEndContentDescriptionId;
    private final ColorStateList mTrackActiveColor;
    private final ColorStateList mTrackInactiveColor;
    private final ColorStateList mThumbColor;
    private final ColorStateList mHaloColor;
    private final int mTrackHeight;
    private final int mTrackInsideCornerSize;
    private final int mTrackStopIndicatorSize;
    private final int mThumbWidth;
    private final int mThumbHeight;
    private final int mThumbElevation;
    private final int mThumbStrokeWidth;
    private final int mThumbTrackGapSize;
    private final int mTickRadius;
    @Nullable private Slider mSlider;
    private int mSliderValue;
    private int mMin;
    private int mMax;
    private int mSliderIncrement;
    private boolean mAdjustable;
    private boolean mTrackingTouch;

    /**
     * Listener reacting to the user pressing DPAD left/right keys if {@code
     * adjustable} attribute is set to true; it transfers the key presses to the {@link Slider}
     * to be handled accordingly.
     */
    private final View.OnKeyListener mSliderKeyListener = new View.OnKeyListener() {
        @Override
        public boolean onKey(@NonNull View v, int keyCode, @NonNull KeyEvent event) {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }

            if (!mAdjustable && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
                // Right or left keys are pressed when in non-adjustable mode; Skip the keys.
                return false;
            }

            // We don't want to propagate the click keys down to the Slider since it will
            // create the ripple effect for the thumb.
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                return false;
            }

            if (mSlider == null) {
                Log.e(TAG, "Slider view is null and hence cannot be adjusted.");
                return false;
            }
            return mSlider.onKeyDown(keyCode, event);
        }
    };
    /**
     * Listener reacting to the {@link Slider} touch event by the user
     */
    private final Slider.OnSliderTouchListener mTouchListener = new Slider.OnSliderTouchListener() {
        @Override
        public void onStopTrackingTouch(@NonNull Slider slider) {
            mTrackingTouch = false;
            if ((int) slider.getValue() != mSliderValue) {
                syncValueInternal(slider);
            }
        }

        @Override
        public void onStartTrackingTouch(@NonNull Slider slider) {
            mTrackingTouch = true;
        }
    };
    private LabelFormatter mLabelFormater;
    // Whether the SliderPreference should continuously save the Slider value while it is being
    // dragged.
    private boolean mUpdatesContinuously;
    /**
     * Listener reacting to the {@link Slider} changing value by the user
     */
    private final Slider.OnChangeListener mChangeListener = new Slider.OnChangeListener() {
        @Override
        public void onValueChange(@NonNull Slider slider, float value, boolean fromUser) {
            if (fromUser && (mUpdatesContinuously || !mTrackingTouch)) {
                syncValueInternal(slider);
            }
        }
    };
    // Whether to show the Slider value TextView next to the bar
    private boolean mShowSliderValue;

    public SliderPreference(@NonNull Context context,
            @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setLayoutResource(R.layout.settingslib_expressive_preference_slider);

        TypedArray a = context.obtainStyledAttributes(
                attrs, androidx.preference.R.styleable.SeekBarPreference, defStyleAttr,
                0 /*defStyleRes*/);

        // The ordering of these two statements are important. If we want to set max first, we need
        // to perform the same steps by changing min/max to max/min as following:
        // mMax = a.getInt(...) and setMin(...).
        mMin = a.getInt(androidx.preference.R.styleable.SeekBarPreference_min, 0);
        setMax(a.getInt(androidx.preference.R.styleable.SeekBarPreference_android_max, 100));
        setSliderIncrement(
                a.getInt(androidx.preference.R.styleable.SeekBarPreference_seekBarIncrement, 0));
        mAdjustable = a.getBoolean(androidx.preference.R.styleable.SeekBarPreference_adjustable,
                true);
        mShowSliderValue = a.getBoolean(
                androidx.preference.R.styleable.SeekBarPreference_showSeekBarValue, false);
        mUpdatesContinuously = a.getBoolean(
                androidx.preference.R.styleable.SeekBarPreference_updatesContinuously,
                false);
        a.recycle();

        a = context.obtainStyledAttributes(attrs,
                R.styleable.SliderPreference);
        mTextStartId = a.getResourceId(
                R.styleable.SliderPreference_textStart, /* defValue= */ 0);
        mTextEndId = a.getResourceId(
                R.styleable.SliderPreference_textEnd, /* defValue= */ 0);
        mIconStartId = a.getResourceId(
                R.styleable.SliderPreference_iconStart, /* defValue= */ 0);
        mIconEndId = a.getResourceId(
                R.styleable.SliderPreference_iconEnd, /* defValue= */ 0);

        mIconStartContentDescriptionId = a.getResourceId(
                R.styleable.SliderPreference_iconStartContentDescription,
                /* defValue= */ 0);

        mIconEndContentDescriptionId = a.getResourceId(
                R.styleable.SliderPreference_iconEndContentDescription,
                /* defValue= */ 0);
        a.recycle();

        mTrackActiveColor = context.getColorStateList(
                R.color.settingslib_expressive_color_slider_track_active);
        mTrackInactiveColor = context.getColorStateList(
                R.color.settingslib_expressive_color_slider_track_inactive);
        mThumbColor = context.getColorStateList(
                R.color.settingslib_expressive_color_slider_thumb);
        mHaloColor = context.getColorStateList(R.color.settingslib_expressive_color_slider_halo);

        Resources res = context.getResources();
        mTrackHeight = res.getDimensionPixelSize(
                R.dimen.settingslib_expressive_slider_track_height);
        mTrackInsideCornerSize = res.getDimensionPixelSize(
                R.dimen.settingslib_expressive_slider_track_inside_corner_size);
        mTrackStopIndicatorSize = res.getDimensionPixelSize(
                R.dimen.settingslib_expressive_slider_track_stop_indicator_size);
        mThumbWidth = res.getDimensionPixelSize(R.dimen.settingslib_expressive_slider_thumb_width);
        mThumbHeight = res.getDimensionPixelSize(
                R.dimen.settingslib_expressive_slider_thumb_height);
        mThumbElevation = res.getDimensionPixelSize(
                R.dimen.settingslib_expressive_slider_thumb_elevation);
        mThumbStrokeWidth = res.getDimensionPixelSize(
                R.dimen.settingslib_expressive_slider_thumb_stroke_width);
        mThumbTrackGapSize = res.getDimensionPixelSize(
                R.dimen.settingslib_expressive_slider_thumb_track_gap_size);
        mTickRadius = res.getDimensionPixelSize(R.dimen.settingslib_expressive_slider_tick_radius);
    }

    /**
     * Constructor that is called when inflating a preference from XML. This is called when a
     * preference is being constructed from an XML file, supplying attributes that were specified
     * in the XML file. This version uses a default style of 0, so the only attribute values
     * applied are those in the Context's Theme and the given AttributeSet.
     *
     * @param context The Context this is associated with, through which it can access the
     *                current theme, resources, etc.
     * @param attrs   The attributes of the XML tag that is inflating the preference
     */
    public SliderPreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0 /* defStyleAttr */);
    }

    /**
     * Constructor to create a slider preference.
     *
     * @param context The Context this is associated with, through which it can access the
     *                current theme, resources, etc.
     */
    public SliderPreference(@NonNull Context context) {
        this(context, null);
    }

    private static void setIconViewAndFrameEnabled(View iconView, ViewGroup iconFrame,
            boolean enabled) {
        iconView.setEnabled(enabled);
        iconFrame.setEnabled(enabled);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        holder.itemView.setOnKeyListener(mSliderKeyListener);
        mSlider = (Slider) holder.findViewById(R.id.slider);

        if (mSlider == null) {
            Log.e(TAG, "Slider is null in onBindViewHolder.");
            return;
        }

        if (mShowSliderValue) {
            mSlider.setLabelBehavior(LabelFormatter.LABEL_FLOATING);
        } else {
            mSlider.setLabelBehavior(LabelFormatter.LABEL_GONE);
        }
        if (mLabelFormater != null) {
            mSlider.setLabelFormatter(mLabelFormater);
        }
        if (mSliderIncrement != 0) {
            mSlider.setStepSize(mSliderIncrement);
        } else {
            mSliderIncrement = (int) (mSlider.getStepSize());
        }
        mSlider.setValueFrom(mMin);
        mSlider.setValueTo(mMax);
        mSlider.setValue(mSliderValue);
        mSlider.addOnSliderTouchListener(mTouchListener);
        mSlider.addOnChangeListener(mChangeListener);
        mSlider.setEnabled(isEnabled());

        // Set up slider color
        mSlider.setTrackActiveTintList(mTrackActiveColor);
        mSlider.setTrackInactiveTintList(mTrackInactiveColor);
        mSlider.setThumbTintList(mThumbColor);
        mSlider.setHaloTintList(mHaloColor);
        mSlider.setTickActiveTintList(mTrackInactiveColor);
        mSlider.setTickInactiveTintList(mTrackActiveColor);

        // Set up slider size
        if (SettingsThemeHelper.isExpressiveTheme(getContext())) {
            mSlider.setTrackHeight(mTrackHeight);
            // need to drop 1.12.0 to Android
            mSlider.setTrackInsideCornerSize(mTrackInsideCornerSize);
            mSlider.setTrackStopIndicatorSize(mTrackStopIndicatorSize);
            mSlider.setThumbWidth(mThumbWidth);
            mSlider.setThumbHeight(mThumbHeight);
            mSlider.setThumbElevation(mThumbElevation);
            mSlider.setThumbStrokeWidth(mThumbStrokeWidth);
            mSlider.setThumbTrackGapSize(mThumbTrackGapSize);
            mSlider.setTickActiveRadius(mTickRadius);
            mSlider.setTickInactiveRadius(mTickRadius);
        }

        TextView startText = (TextView) holder.findViewById(android.R.id.text1);
        if (mTextStartId > 0 && startText != null) {
            startText.setText(mTextStartId);
        }

        TextView endText = (TextView) holder.findViewById(android.R.id.text2);
        if (mTextEndId > 0 && endText != null) {
            endText.setText(mTextEndId);
        }

        View labelFrame = holder.findViewById(R.id.label_frame);
        if (labelFrame != null) {
            boolean isValidTextResIdExist = mTextStartId > 0 || mTextEndId > 0;
            labelFrame.setVisibility(isValidTextResIdExist ? View.VISIBLE : View.GONE);
        }

        ImageView iconStartView = (ImageView) holder.findViewById(R.id.icon_start);
        updateIconStartIfNeeded(iconStartView);

        ImageView iconEndView = (ImageView) holder.findViewById(R.id.icon_end);
        updateIconEndIfNeeded(iconEndView);
    }

    /**
     * Gets the lower bound set on the {@link Slider}.
     *
     * @return The lower bound set
     */
    public int getMin() {
        return mMin;
    }

    /**
     * Sets the lower bound on the {@link Slider}.
     *
     * @param min The lower bound to set
     */
    public void setMin(int min) {
        if (min > mMax) {
            min = mMax;
        }
        if (min != mMin) {
            mMin = min;
            notifyChanged();
        }
    }

    /**
     * Gets the upper bound set on the {@link Slider}.
     *
     * @return The upper bound set
     */
    public int getMax() {
        return mMax;
    }

    /**
     * Sets the upper bound on the {@link Slider}.
     *
     * @param max The upper bound to set
     */
    public final void setMax(int max) {
        if (max < mMin) {
            max = mMin;
        }
        if (max != mMax) {
            mMax = max;
            notifyChanged();
        }
    }

    public final int getSliderIncrement() {
        return mSliderIncrement;
    }

    /**
     * Sets the increment amount on the {@link Slider} for each arrow key press.
     *
     * @param sliderIncrement The amount to increment or decrement when the user presses an
     *                        arrow key.
     */
    public final void setSliderIncrement(int sliderIncrement) {
        if (sliderIncrement != mSliderIncrement) {
            mSliderIncrement = Math.min(mMax - mMin, Math.abs(sliderIncrement));
            notifyChanged();
        }
    }

    /**
     * Gets whether the {@link Slider} should respond to the left/right keys.
     *
     * @return Whether the {@link Slider} should respond to the left/right keys
     */
    public boolean isAdjustable() {
        return mAdjustable;
    }

    /**
     * Sets whether the {@link Slider} should respond to the left/right keys.
     *
     * @param adjustable Whether the {@link Slider} should respond to the left/right keys
     */
    public void setAdjustable(boolean adjustable) {
        mAdjustable = adjustable;
    }

    /**
     * Gets whether the {@link SliderPreference} should continuously save the {@link Slider} value
     * while it is being dragged. Note that when the value is true,
     * {@link Preference.OnPreferenceChangeListener} will be called continuously as well.
     *
     * @return Whether the {@link SliderPreference} should continuously save the {@link Slider}
     * value while it is being dragged
     * @see #setUpdatesContinuously(boolean)
     */
    public boolean getUpdatesContinuously() {
        return mUpdatesContinuously;
    }

    /**
     * Sets whether the {@link SliderPreference} should continuously save the {@link Slider} value
     * while it is being dragged.
     *
     * @param updatesContinuously Whether the {@link SliderPreference} should continuously save
     *                            the {@link Slider} value while it is being dragged
     * @see #getUpdatesContinuously()
     */
    public void setUpdatesContinuously(boolean updatesContinuously) {
        mUpdatesContinuously = updatesContinuously;
    }

    /**
     * Gets whether the current {@link Slider} value is displayed to the user.
     *
     * @return Whether the current {@link Slider} value is displayed to the user
     * @see #setShowSliderValue(boolean)
     */
    public boolean getShowSliderValue() {
        return mShowSliderValue;
    }

    /**
     * Sets whether the current {@link Slider} value is displayed to the user.
     *
     * @param showSliderValue Whether the current {@link Slider} value is displayed to the user
     * @see #getShowSliderValue()
     */
    public void setShowSliderValue(boolean showSliderValue) {
        mShowSliderValue = showSliderValue;
        notifyChanged();
    }

    public void setLabelFormater(@Nullable LabelFormatter formater) {
        mLabelFormater = formater;
    }

    /**
     * Gets the current progress of the {@link Slider}.
     *
     * @return The current progress of the {@link Slider}
     */
    public int getValue() {
        return mSliderValue;
    }

    /**
     * Sets the current progress of the {@link Slider}.
     *
     * @param sliderValue The current progress of the {@link Slider}
     */
    public void setValue(int sliderValue) {
        setValueInternal(sliderValue, true);
    }

    @Override
    protected void onSetInitialValue(@Nullable Object defaultValue) {
        if (defaultValue == null) {
            defaultValue = 0;
        }
        setValue(getPersistedInt((Integer) defaultValue));
    }

    @Override
    protected @Nullable Object onGetDefaultValue(@NonNull TypedArray a, int index) {
        return a.getInt(index, 0);
    }

    /**
     * Persist the {@link Slider}'s Slider value if callChangeListener returns true, otherwise
     * set the {@link Slider}'s value to the stored value.
     */
    void syncValueInternal(@NonNull Slider slider) {
        int sliderValue = mMin + (int) slider.getValue();
        if (sliderValue != mSliderValue) {
            if (callChangeListener(sliderValue)) {
                setValueInternal(sliderValue, false);
                // TODO: mHapticFeedbackMode
            } else {
                slider.setValue(mSliderValue);
            }
        }
    }

    private void setValueInternal(int sliderValue, boolean notifyChanged) {
        if (sliderValue < mMin) {
            sliderValue = mMin;
        }
        if (sliderValue > mMax) {
            sliderValue = mMax;
        }

        if (sliderValue != mSliderValue) {
            mSliderValue = sliderValue;
            persistInt(sliderValue);
            if (notifyChanged) {
                notifyChanged();
            }
        }
    }

    @Nullable
    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        if (isPersistent()) {
            // No need to save instance state since it's persistent
            return superState;
        }

        // Save the instance state
        SavedState myState = new SavedState(superState);
        myState.mSliderValue = mSliderValue;
        myState.mMin = mMin;
        myState.mMax = mMax;
        return myState;
    }

    @Override
    protected void onRestoreInstanceState(@Nullable Parcelable state) {
        if (state == null || !state.getClass().equals(SavedState.class)) {
            // Didn't save state for us in onSaveInstanceState
            super.onRestoreInstanceState(state);
            return;
        }

        // Restore the instance state
        SavedState myState = (SavedState) state;
        super.onRestoreInstanceState(myState.getSuperState());
        mSliderValue = myState.mSliderValue;
        mMin = myState.mMin;
        mMax = myState.mMax;
        notifyChanged();
    }

    private void updateIconStartIfNeeded(ImageView icon) {
        if (icon == null) {
            return;
        }
        ViewGroup iconFrame = (ViewGroup) icon.getParent();
        if (iconFrame == null) {
            return;
        }

        if (mIconStartId == 0 || mSliderIncrement == 0) {
            iconFrame.setVisibility(View.GONE);
            return;
        }

        if (icon.getDrawable() == null) {
            icon.setImageResource(mIconStartId);
        }

        if (mIconStartContentDescriptionId != 0) {
            String contentDescription =
                    iconFrame.getContext().getString(mIconStartContentDescriptionId);
            iconFrame.setContentDescription(contentDescription);
        }

        iconFrame.setOnClickListener((view) -> {
            if (mSliderValue > 0) {
                setValue(mSliderValue - mSliderIncrement);
            }
        });

        iconFrame.setVisibility(View.VISIBLE);
        setIconViewAndFrameEnabled(icon, iconFrame, mSliderValue > mMin);
    }

    private void updateIconEndIfNeeded(ImageView icon) {
        if (icon == null) {
            return;
        }
        ViewGroup iconFrame = (ViewGroup) icon.getParent();
        if (iconFrame == null) {
            return;
        }

        if (mIconEndId == 0 || mSliderIncrement == 0) {
            iconFrame.setVisibility(View.GONE);
            return;
        }

        if (icon.getDrawable() == null) {
            icon.setImageResource(mIconEndId);
        }

        if (mIconEndContentDescriptionId != 0) {
            String contentDescription =
                    iconFrame.getContext().getString(mIconEndContentDescriptionId);
            iconFrame.setContentDescription(contentDescription);
        }

        iconFrame.setOnClickListener((view) -> {
            if (mSliderValue < mMax) {
                setValue(mSliderValue + mSliderIncrement);
            }
        });

        iconFrame.setVisibility(View.VISIBLE);
        setIconViewAndFrameEnabled(icon, iconFrame, mSliderValue < mMax);
    }

    /**
     * SavedState, a subclass of {@link BaseSavedState}, will store the state of this preference.
     *
     * <p>It is important to always call through to super methods.
     */
    private static class SavedState extends BaseSavedState {
        public static final Parcelable.Creator<SavedState> CREATOR =
                new Parcelable.Creator<>() {
                    @Override
                    @NonNull
                    public SavedState createFromParcel(@NonNull Parcel in) {
                        return new SavedState(in);
                    }

                    @Override
                    @NonNull
                    public SavedState[] newArray(int size) {
                        return new SavedState[size];
                    }
                };

        int mSliderValue;
        int mMin;
        int mMax;

        SavedState(Parcel source) {
            super(source);

            // Restore the click counter
            mSliderValue = source.readInt();
            mMin = source.readInt();
            mMax = source.readInt();
        }

        SavedState(Parcelable superState) {
            super(superState);
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            super.writeToParcel(dest, flags);

            // Save the click counter
            dest.writeInt(mSliderValue);
            dest.writeInt(mMin);
            dest.writeInt(mMax);
        }
    }
}
