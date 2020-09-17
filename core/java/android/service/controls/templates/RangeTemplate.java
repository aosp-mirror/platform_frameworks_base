/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.service.controls.templates;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.service.controls.Control;
import android.service.controls.actions.FloatAction;

/**
 * A template for a {@link Control} with inputs in a "continuous" range of values.
 *
 * @see FloatAction
 */
public final class RangeTemplate extends ControlTemplate {

    private static final @TemplateType int TYPE = TYPE_RANGE;
    private static final String KEY_MIN_VALUE = "key_min_value";
    private static final String KEY_MAX_VALUE = "key_max_value";
    private static final String KEY_CURRENT_VALUE = "key_current_value";
    private static final String KEY_STEP_VALUE = "key_step_value";
    private static final String KEY_FORMAT_STRING = "key_format_string";

    private final float mMinValue;
    private final float mMaxValue;
    private final float mCurrentValue;
    private final float mStepValue;
    private final @NonNull CharSequence mFormatString;

    /**
     * Construct a new {@link RangeTemplate}.
     *
     * The range must be valid, meaning:
     * <ul>
     *     <li> {@code minValue} < {@code maxValue}
     *     <li> {@code minValue} < {@code currentValue}
     *     <li> {@code currentValue} < {@code maxValue}
     *     <li> 0 < {@code stepValue}
     * </ul>
     * <p>
     * The current value of the Control will be formatted accordingly.
     *
     * @param templateId the identifier for this template object
     * @param minValue minimum value for the input
     * @param maxValue maximum value for the input
     * @param currentValue the current value of the {@link Control} containing this object.
     * @param stepValue minimum value of increments/decrements when interacting with this control.
     * @param formatString a formatting string as per {@link String#format} used to display the
     *                    {@code currentValue}. If {@code null} is passed, the "%.1f" is used.
     * @throws IllegalArgumentException if the parameters passed do not make a valid range.
     */
    public RangeTemplate(@NonNull String templateId,
            float minValue,
            float maxValue,
            float currentValue,
            float stepValue,
            @Nullable CharSequence formatString) {
        super(templateId);
        mMinValue = minValue;
        mMaxValue = maxValue;
        mCurrentValue = currentValue;
        mStepValue = stepValue;
        if (formatString != null) {
            mFormatString = formatString;
        } else {
            mFormatString = "%.1f";
        }
        validate();
    }

    /**
     * Construct a new {@link RangeTemplate} from a {@link Bundle}.
     *
     * @throws IllegalArgumentException if the parameters passed do not make a valid range
     * @see RangeTemplate#RangeTemplate(String, float, float, float, float, CharSequence)
     * @hide
     */
    RangeTemplate(Bundle b) {
        super(b);
        mMinValue = b.getFloat(KEY_MIN_VALUE);
        mMaxValue = b.getFloat(KEY_MAX_VALUE);
        mCurrentValue = b.getFloat(KEY_CURRENT_VALUE);
        mStepValue = b.getFloat(KEY_STEP_VALUE);
        mFormatString = b.getCharSequence(KEY_FORMAT_STRING, "%.1f");
        validate();
    }

    /**
     * The minimum value for this range.
     */
    public float getMinValue() {
        return mMinValue;
    }

    /**
     * The maximum value for this range.
     */
    public float getMaxValue() {
        return mMaxValue;
    }

    /**
     * The current value for this range.
     */
    public float getCurrentValue() {
        return mCurrentValue;
    }

    /**
     * The value of the smallest increment or decrement that can be performed on this range.
     */
    public float getStepValue() {
        return mStepValue;
    }

    /**
     * Formatter for generating a user visible {@link String} representing the value
     *         returned by {@link RangeTemplate#getCurrentValue}.
     * @return a formatting string as specified in {@link String#format}
     */
    @NonNull
    public CharSequence getFormatString() {
        return mFormatString;
    }

    /**
     * @return {@link ControlTemplate#TYPE_RANGE}
     */
    @Override
    public int getTemplateType() {
        return TYPE;
    }

    /**
     * @return
     * @hide
     */
    @Override
    @NonNull
    Bundle getDataBundle() {
        Bundle b = super.getDataBundle();
        b.putFloat(KEY_MIN_VALUE, mMinValue);
        b.putFloat(KEY_MAX_VALUE, mMaxValue);
        b.putFloat(KEY_CURRENT_VALUE, mCurrentValue);
        b.putFloat(KEY_STEP_VALUE, mStepValue);
        b.putCharSequence(KEY_FORMAT_STRING, mFormatString);
        return b;
    }

    /**
     * Validate constructor parameters
     *
     * @throws IllegalArgumentException if the parameters passed do not make a valid range
     */
    private void validate() {
        if (Float.compare(mMinValue, mMaxValue) > 0) {
            throw new IllegalArgumentException(
                    String.format("minValue=%f > maxValue=%f", mMinValue, mMaxValue));
        }
        if (Float.compare(mMinValue, mCurrentValue) > 0) {
            throw new IllegalArgumentException(
                    String.format("minValue=%f > currentValue=%f", mMinValue, mCurrentValue));
        }
        if (Float.compare(mCurrentValue, mMaxValue) > 0) {
            throw new IllegalArgumentException(
                    String.format("currentValue=%f > maxValue=%f", mCurrentValue, mMaxValue));
        }
        if (mStepValue <= 0) {
            throw new IllegalArgumentException(String.format("stepValue=%f <= 0", mStepValue));
        }
    }
}
