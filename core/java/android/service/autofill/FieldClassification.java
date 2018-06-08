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

import static android.view.autofill.Helper.sDebug;

import android.annotation.NonNull;
import android.os.Parcel;
import android.view.autofill.Helper;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Represents the <a href="AutofillService.html#FieldClassification">field classification</a>
 * results for a given field.
 */
public final class FieldClassification {

    private final ArrayList<Match> mMatches;

    /** @hide */
    public FieldClassification(@NonNull ArrayList<Match> matches) {
        mMatches = Preconditions.checkNotNull(matches);
        Collections.sort(mMatches, new Comparator<Match>() {
            @Override
            public int compare(Match o1, Match o2) {
                if (o1.mScore > o2.mScore) return -1;
                if (o1.mScore < o2.mScore) return 1;
                return 0;
            }}
        );
    }

    /**
     * Gets the {@link Match matches} with the highest {@link Match#getScore() scores} (sorted in
     * descending order).
     *
     * <p><b>Note:</b> There's no guarantee of how many matches will be returned. In fact,
     * the Android System might return just the top match to minimize the impact of field
     * classification in the device's health.
     */
    @NonNull
    public List<Match> getMatches() {
        return mMatches;
    }

    @Override
    public String toString() {
        if (!sDebug) return super.toString();

        return "FieldClassification: " + mMatches;
    }

    private void writeToParcel(Parcel parcel) {
        parcel.writeInt(mMatches.size());
        for (int i = 0; i < mMatches.size(); i++) {
            mMatches.get(i).writeToParcel(parcel);
        }
    }

    private static FieldClassification readFromParcel(Parcel parcel) {
        final int size = parcel.readInt();
        final ArrayList<Match> matches = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            matches.add(i, Match.readFromParcel(parcel));
        }

        return new FieldClassification(matches);
    }

    static FieldClassification[] readArrayFromParcel(Parcel parcel) {
        final int length = parcel.readInt();
        final FieldClassification[] fcs = new FieldClassification[length];
        for (int i = 0; i < length; i++) {
            fcs[i] = readFromParcel(parcel);
        }
        return fcs;
    }

    static void writeArrayToParcel(@NonNull Parcel parcel, @NonNull FieldClassification[] fcs) {
        parcel.writeInt(fcs.length);
        for (int i = 0; i < fcs.length; i++) {
            fcs[i].writeToParcel(parcel);
        }
    }

    /**
     * Represents the score of a {@link UserData} entry for the field.
     */
    public static final class Match {

        private final String mCategoryId;
        private final float mScore;

        /** @hide */
        public Match(String categoryId, float score) {
            mCategoryId = Preconditions.checkNotNull(categoryId);
            mScore = score;
        }

        /**
         * Gets the category id of the {@link UserData} entry.
         */
        @NonNull
        public String getCategoryId() {
            return mCategoryId;
        }

        /**
         * Gets a classification score for the value of this field compared to the value of the
         * {@link UserData} entry.
         *
         * <p>The score is based in a comparison of the field value and the user data entry, and it
         * ranges from {@code 0.0F} to {@code 1.0F}:
         * <ul>
         *   <li>{@code 1.0F} represents a full match ({@code 100%}).
         *   <li>{@code 0.0F} represents a full mismatch ({@code 0%}).
         *   <li>Any other value is a partial match.
         * </ul>
         *
         * <p>How the score is calculated depends on the
         * {@link UserData.Builder#setFieldClassificationAlgorithm(String, android.os.Bundle)
         * algorithm} used.
         */
        public float getScore() {
            return mScore;
        }

        @Override
        public String toString() {
            if (!sDebug) return super.toString();

            final StringBuilder string = new StringBuilder("Match: categoryId=");
            Helper.appendRedacted(string, mCategoryId);
            return string.append(", score=").append(mScore).toString();
        }

        private void writeToParcel(@NonNull Parcel parcel) {
            parcel.writeString(mCategoryId);
            parcel.writeFloat(mScore);
        }

        private static Match readFromParcel(@NonNull Parcel parcel) {
            return new Match(parcel.readString(), parcel.readFloat());
        }
    }
}
