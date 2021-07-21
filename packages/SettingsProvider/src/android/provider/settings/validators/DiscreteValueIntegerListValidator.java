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

package android.provider.settings.validators;

/**
 * Validates the elements in a list with the array of allowed integer values.
 *
 * @hide
 */
class DiscreteValueIntegerListValidator extends ListValidator {
    private int[] mAllowedValues;

    DiscreteValueIntegerListValidator(String listSplitRegex, int[] allowedValues) {
        super(listSplitRegex);
        mAllowedValues = allowedValues;
    }

    @Override
    protected boolean isEntryValid(String entry) {
        return (entry != null);
    }

    @Override
    protected boolean isItemValid(String item) {
        for (int allowedValue : mAllowedValues) {
            try {
                if (Integer.parseInt(item) == allowedValue) {
                    return true;
                }
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return false;
    }
}
