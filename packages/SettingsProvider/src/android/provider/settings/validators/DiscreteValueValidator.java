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

package android.provider.settings.validators;

import android.annotation.Nullable;

import com.android.internal.util.ArrayUtils;

/**
 * Validate a value exists in an array of known good values
 *
 * @hide
 */
public final class DiscreteValueValidator implements Validator {
    private final String[] mValues;

    public DiscreteValueValidator(String[] values) {
        mValues = values;
    }

    @Override
    public boolean validate(@Nullable String value) {
        return ArrayUtils.contains(mValues, value);
    }
}
