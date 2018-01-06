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
package android.ext.services.autofill;

import android.annotation.NonNull;
import android.view.autofill.AutofillValue;

/**
 * Helper used to calculate the classification score between an actual {@link AutofillValue} filled
 * by the user and the expected value predicted by an autofill service.
 */
// TODO(b/70291841): explain algorithm once it's fully implemented
final class EditDistanceScorer {

    private static final EditDistanceScorer sInstance = new EditDistanceScorer();

    public static final String NAME = "EDIT_DISTANCE";

    /**
     * Gets the singleton instance.
     */
    public static EditDistanceScorer getInstance() {
        return sInstance;
    }

    private EditDistanceScorer() {
    }

    /**
     * Returns the classification score between an actual {@link AutofillValue} filled
     * by the user and the expected value predicted by an autofill service.
     *
     * <p>A full-match is {@code 1.0} (representing 100%), a full mismatch is {@code 0.0} and
     * partial mathces are something in between, typically using edit-distance algorithms.
     *
     */
    public float getScore(@NonNull AutofillValue actualValue, @NonNull String userDataValue) {
        if (actualValue == null || !actualValue.isText() || userDataValue == null) return 0;
        // TODO(b/70291841): implement edit distance - currently it's returning either 0, 100%, or
        // partial match when number of chars match
        final String textValue = actualValue.getTextValue().toString();
        final int total = textValue.length();
        if (total != userDataValue.length()) return 0F;

        int matches = 0;
        for (int i = 0; i < total; i++) {
            if (Character.toLowerCase(textValue.charAt(i)) == Character
                    .toLowerCase(userDataValue.charAt(i))) {
                matches++;
            }
        }

        return ((float) matches) / total;
    }
}
