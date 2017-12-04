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
import android.annotation.TestApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.autofill.Helper;

import com.android.internal.util.Preconditions;

/**
 * Gets the <a href="#FieldsClassification">fields classification</a> results for a given field.
 *
 * TODO(b/67867469):
 * - improve javadoc
 * - unhide / remove testApi
 *
 * @hide
 */
@TestApi
public final class FieldClassification implements Parcelable {

    private final Match mMatch;

    /** @hide */
    public FieldClassification(@NonNull Match match) {
        mMatch = Preconditions.checkNotNull(match);
    }

    /**
     * Gets the {@link Match} with the highest {@link Match#getScore() score} for the field.
     */
    @NonNull
    public Match getTopMatch() {
        return mMatch;
    }

    @Override
    public String toString() {
        if (!sDebug) return super.toString();

        return "FieldClassification: " + mMatch;
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
        parcel.writeParcelable(mMatch, flags);
    }

    public static final Parcelable.Creator<FieldClassification> CREATOR =
            new Parcelable.Creator<FieldClassification>() {

        @Override
        public FieldClassification createFromParcel(Parcel parcel) {
            return new FieldClassification(parcel.readParcelable(null));
        }

        @Override
        public FieldClassification[] newArray(int size) {
            return new FieldClassification[size];
        }
    };

    /**
     * Gets the score of a {@link UserData} entry for the field.
     *
     * TODO(b/67867469):
     * - improve javadoc
     * - unhide / remove testApi
     *
     * @hide
     */
    @TestApi
    public static final class Match implements Parcelable {

        private final String mRemoteId;
        private final int mScore;

        /** @hide */
        public Match(String remoteId, int score) {
            mRemoteId = Preconditions.checkNotNull(remoteId);
            mScore = score;
        }

        /**
         * Gets the remote id of the {@link UserData} entry.
         */
        @NonNull
        public String getRemoteId() {
            return mRemoteId;
        }

        /**
         * Gets a score between the value of this field and the value of the {@link UserData} entry.
         *
         * <p>The score is based in a case-insensitive comparisson of all characters from both the
         * field value and the user data entry, and it ranges from {@code 0} to {@code 1000000}:
         * <ul>
         *   <li>{@code 1000000} represents a full match ({@code 100.0000%}).
         *   <li>{@code 0} represents a full mismatch ({@code 0.0000%}).
         *   <li>Any other value is a partial match.
         * </ul>
         *
         * <p>How the score is calculated depends on the algorithm used by the Android System.
         * For example, if the user  data is {@code "abc"} and the field value us {@code " abc"},
         * the result could be:
         * <ul>
         *   <li>{@code 1000000} if the algorithm trims the values.
         *   <li>{@code 0} if the algorithm compares the values sequentially.
         *   <li>{@code 750000} if the algorithm consideres that 3/4 (75%) of the characters match.
         * </ul>
         *
         * <p>Currently, the autofill service cannot configure the algorithm.
         */
        public int getScore() {
            return mScore;
        }

        @Override
        public String toString() {
            if (!sDebug) return super.toString();

            final StringBuilder string = new StringBuilder("Match: remoteId=");
            Helper.appendRedacted(string, mRemoteId);
            return string.append(", score=").append(mScore).toString();
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
            parcel.writeString(mRemoteId);
            parcel.writeInt(mScore);
        }

        @SuppressWarnings("hiding")
        public static final Parcelable.Creator<Match> CREATOR = new Parcelable.Creator<Match>() {

            @Override
            public Match createFromParcel(Parcel parcel) {
                return new Match(parcel.readString(), parcel.readInt());
            }

            @Override
            public Match[] newArray(int size) {
                return new Match[size];
            }
        };
    }
}
