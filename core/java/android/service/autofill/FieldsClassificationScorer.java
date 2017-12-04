/*
 * Copyright (C) 2017 The Android Open Source Project
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
package android.service.autofill;

import android.annotation.NonNull;
import android.annotation.TestApi;
import android.view.autofill.AutofillValue;

/**
 * Helper used to calculate the classification score between an actual {@link AutofillValue} filled
 * by the user and the expected value predicted by an autofill service.
 *
 * @hide
 */
@TestApi
public final class FieldsClassificationScorer {

    private static final int MAX_VALUE = 100_0000; // 100.0000%

    /**
     * Returns the classification score between an actual {@link AutofillValue} filled
     * by the user and the expected value predicted by an autofill service.
     *
     * <p>A full-match is {@code 1000000} (representing 100.0000%), a full mismatch is {@code 0} and
     * partial mathces are something in between, typically using edit-distance algorithms.
     */
    public static int getScore(@NonNull AutofillValue actualValue, @NonNull String userData) {
        // TODO(b/67867469): implement edit distance - currently it's returning either 0 or 100%
        if (actualValue == null || !actualValue.isText() || userData == null) return 0;
        return actualValue.getTextValue().toString().equalsIgnoreCase(userData) ? MAX_VALUE : 0;
    }

    private FieldsClassificationScorer() {
        throw new UnsupportedOperationException("contains only static methods");
    }
}
