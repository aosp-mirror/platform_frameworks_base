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
import android.os.Parcel;
import android.util.Log;

/**
 * @hide
 */
public final class CoordinatedRangeTemplate extends ControlTemplate {

    private static final String TAG = "CoordinatedRangeTemplate";

    private static final @TemplateType int TYPE = TYPE_COORD_RANGE;
    private static final String KEY_RANGE_LOW = "key_range_low";
    private static final String KEY_RANGE_HIGH = "key_range_high";
    private static final String KEY_MIN_GAP = "key_min_gap";

    private final @NonNull RangeTemplate mRangeLow;
    private final @NonNull RangeTemplate mRangeHigh;
    private final float mMinGap;

    public CoordinatedRangeTemplate(
            @NonNull String templateId,
            float minGap,
            @NonNull RangeTemplate rangeLow,
            @NonNull RangeTemplate rangeHigh) {
        super(templateId);
        mRangeLow = rangeLow;
        mRangeHigh = rangeHigh;
        if (minGap < 0) {
            Log.e(TAG, "minGap must be non-negative. Setting to 0");
            mMinGap = 0;
        } else {
            mMinGap = minGap;
        }
        validateRanges();
    }

    public CoordinatedRangeTemplate(
            @NonNull String templateId,
            float minGap,
            float minValueLow,
            float maxValueLow,
            float currentValueLow,
            float minValueHigh,
            float maxValueHigh,
            float currentValueHigh,
            float stepValue,
            @Nullable CharSequence formatString) {
        this(templateId,
                minGap,
            new RangeTemplate("",
                minValueLow, maxValueLow, currentValueLow, stepValue, formatString),
            new RangeTemplate("",
                minValueHigh, maxValueHigh, currentValueHigh, stepValue, formatString));
    }

    CoordinatedRangeTemplate(Bundle b) {
        super(b);
        mRangeLow = b.getParcelable(KEY_RANGE_LOW);
        mRangeHigh = b.getParcelable(KEY_RANGE_HIGH);
        mMinGap = b.getFloat(KEY_MIN_GAP);
        validateRanges();
    }

    @NonNull
    public RangeTemplate getRangeLow() {
        return mRangeLow;
    }

    @NonNull
    public RangeTemplate getRangeHigh() {
        return mRangeHigh;
    }

    public float getMinValueLow() {
        return mRangeLow.getMinValue();
    }

    public float getMaxValueLow() {
        return mRangeLow.getMaxValue();
    }

    public float getCurrentValueLow() {
        return mRangeLow.getCurrentValue();
    }

    public float getMinValueHigh() {
        return mRangeHigh.getMinValue();
    }

    public float getMaxValueHigh() {
        return mRangeHigh.getMaxValue();
    }

    public float getCurrentValueHigh() {
        return mRangeHigh.getCurrentValue();
    }

    public float getStepValue() {
        return mRangeLow.getStepValue();
    }

    public float getMinGap() {
        return mMinGap;
    }

    @NonNull
    public CharSequence getFormatString() {
        return mRangeLow.getFormatString();
    }

    @Override
    public int getTemplateType() {
        return TYPE;
    }

    @Override
    protected Bundle getDataBundle() {
        Bundle b = super.getDataBundle();
        b.putParcelable(KEY_RANGE_LOW, mRangeLow);
        b.putParcelable(KEY_RANGE_HIGH, mRangeHigh);
        return b;
    }

    private void validateRanges() {
        if (Float.compare(mRangeLow.getStepValue(), mRangeHigh.getStepValue()) != 0) {
            throw new IllegalArgumentException(
                    String.format("lowStepValue=%f != highStepValue=%f",
                            mRangeLow.getStepValue(), mRangeHigh.getStepValue()));
        }
        if (!mRangeLow.getFormatString().equals(mRangeHigh.getFormatString())) {
            throw new IllegalArgumentException(
                    String.format("lowFormatString=%s != highFormatString=%s",
                            mRangeLow.getFormatString(), mRangeHigh.getFormatString()));
        }
        if (mMinGap > mRangeHigh.getCurrentValue() - mRangeLow.getCurrentValue()) {
            throw new IllegalArgumentException(
                    String.format("Minimum gap (%f) > Current gap (%f)", mMinGap,
                            mRangeHigh.getCurrentValue() - mRangeLow.getCurrentValue()));
        }
    }

    public static final Creator<CoordinatedRangeTemplate> CREATOR =
            new Creator<CoordinatedRangeTemplate>() {
        @Override
        public CoordinatedRangeTemplate createFromParcel(Parcel source) {
            int type = source.readInt();
            verifyType(type, TYPE);
            return new CoordinatedRangeTemplate(source.readBundle());
        }

        @Override
        public CoordinatedRangeTemplate[] newArray(int size) {
            return new CoordinatedRangeTemplate[size];
        }
    };
}
