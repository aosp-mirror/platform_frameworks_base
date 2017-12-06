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
import android.os.Parcel;
import android.os.Parcelable;
import android.view.autofill.AutofillValue;

/**
 * Helper used to calculate the classification score between an actual {@link AutofillValue} filled
 * by the user and the expected value predicted by an autofill service.
 *
 * TODO(b/67867469):
 * - improve javadoc
 * - document algorithm / copy from InternalScorer
 * - unhide / remove testApi
 * @hide
 */
@TestApi
public final class EditDistanceScorer extends InternalScorer implements Scorer, Parcelable {

    private static final EditDistanceScorer sInstance = new EditDistanceScorer();

    /**
     * Gets the singleton instance.
     */
    public static EditDistanceScorer getInstance() {
        return sInstance;
    }

    private EditDistanceScorer() {
    }

    @Override
    public float getScore(@NonNull AutofillValue actualValue, @NonNull String userData) {
        if (actualValue == null || !actualValue.isText() || userData == null) return 0;
        // TODO(b/67867469): implement edit distance - currently it's returning either 0, 100%, or
        // partial match when number of chars match
        final String textValue = actualValue.getTextValue().toString();
        final int total = textValue.length();
        if (total != userData.length()) return 0F;

        int matches = 0;
        for (int i = 0; i < total; i++) {
            if (Character.toLowerCase(textValue.charAt(i)) == Character
                    .toLowerCase(userData.charAt(i))) {
                matches++;
            }
        }

        return ((float) matches) / total;
    }

    /////////////////////////////////////
    // Object "contract" methods. //
    /////////////////////////////////////
    @Override
    public String toString() {
        return "EditDistanceScorer";
    }

    /////////////////////////////////////
    // Parcelable "contract" methods. //
    /////////////////////////////////////

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        // Do nothing
    }

    public static final Parcelable.Creator<EditDistanceScorer> CREATOR =
            new Parcelable.Creator<EditDistanceScorer>() {
        @Override
        public EditDistanceScorer createFromParcel(Parcel parcel) {
            return EditDistanceScorer.getInstance();
        }

        @Override
        public EditDistanceScorer[] newArray(int size) {
            return new EditDistanceScorer[size];
        }
    };
}
