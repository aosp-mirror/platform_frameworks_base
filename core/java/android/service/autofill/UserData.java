/*
 * Copyright 2017 The Android Open Source Project
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

import static android.provider.Settings.Secure.AUTOFILL_USER_DATA_MAX_FIELD_CLASSIFICATION_IDS_SIZE;
import static android.provider.Settings.Secure.AUTOFILL_USER_DATA_MAX_USER_DATA_SIZE;
import static android.provider.Settings.Secure.AUTOFILL_USER_DATA_MAX_VALUE_LENGTH;
import static android.provider.Settings.Secure.AUTOFILL_USER_DATA_MIN_VALUE_LENGTH;
import static android.view.autofill.Helper.sDebug;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.app.ActivityThread;
import android.content.ContentResolver;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.Settings;
import android.util.Log;
import android.view.autofill.Helper;

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Class used by service to improve autofillable fields detection by tracking the meaning of fields
 * manually edited by the user (when they match values provided by the service).
 *
 * TODO(b/67867469):
 *  - improve javadoc / add link to section on AutofillService
 *  - unhide / remove testApi
 * @hide
 */
@TestApi
public final class UserData implements Parcelable {

    private static final String TAG = "UserData";

    private static final int DEFAULT_MAX_USER_DATA_SIZE = 10;
    private static final int DEFAULT_MAX_FIELD_CLASSIFICATION_IDS_SIZE = 10;
    private static final int DEFAULT_MIN_VALUE_LENGTH = 5;
    private static final int DEFAULT_MAX_VALUE_LENGTH = 100;

    private final InternalScorer mScorer;
    private final String[] mRemoteIds;
    private final String[] mValues;

    private UserData(Builder builder) {
        mScorer = builder.mScorer;
        mRemoteIds = new String[builder.mRemoteIds.size()];
        builder.mRemoteIds.toArray(mRemoteIds);
        mValues = new String[builder.mValues.size()];
        builder.mValues.toArray(mValues);
    }

    /** @hide */
    public InternalScorer getScorer() {
        return mScorer;
    }

    /** @hide */
    public String[] getRemoteIds() {
        return mRemoteIds;
    }

    /** @hide */
    public String[] getValues() {
        return mValues;
    }

    /** @hide */
    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("Scorer: "); pw.println(mScorer);
        // Cannot disclose remote ids or values because they could contain PII
        pw.print(prefix); pw.print("Remote ids size: "); pw.println(mRemoteIds.length);
        for (int i = 0; i < mRemoteIds.length; i++) {
            pw.print(prefix); pw.print(prefix); pw.print(i); pw.print(": ");
            pw.println(Helper.getRedacted(mRemoteIds[i]));
        }
        pw.print(prefix); pw.print("Values size: "); pw.println(mValues.length);
        for (int i = 0; i < mValues.length; i++) {
            pw.print(prefix); pw.print(prefix); pw.print(i); pw.print(": ");
            pw.println(Helper.getRedacted(mValues[i]));
        }
    }

    /** @hide */
    public static void dumpConstraints(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("maxUserDataSize: "); pw.println(getMaxUserDataSize());
        pw.print(prefix); pw.print("maxFieldClassificationIdsSize: ");
        pw.println(getMaxFieldClassificationIdsSize());
        pw.print(prefix); pw.print("minValueLength: "); pw.println(getMinValueLength());
        pw.print(prefix); pw.print("maxValueLength: "); pw.println(getMaxValueLength());
    }

    /**
     * A builder for {@link UserData} objects.
     *
     * TODO(b/67867469): unhide / remove testApi
     *
     * @hide
     */
    @TestApi
    public static final class Builder {
        private final InternalScorer mScorer;
        private final ArrayList<String> mRemoteIds;
        private final ArrayList<String> mValues;
        private boolean mDestroyed;

        /**
         * Creates a new builder for the user data used for <a href="#FieldsClassification">fields
         * classification</a>.
         *
         * @throws IllegalArgumentException if any of the following occurs:
         * <ol>
         *   <li>{@code remoteId} is empty
         *   <li>{@code value} is empty
         *   <li>the length of {@code value} is lower than {@link UserData#getMinValueLength()}
         *   <li>the length of {@code value} is higher than {@link UserData#getMaxValueLength()}
         *   <li>{@code scorer} is not instance of a class provided by the Android System.
         * </ol>
         */
        public Builder(@NonNull Scorer scorer, @NonNull String remoteId, @NonNull String value) {
            Preconditions.checkArgument((scorer instanceof InternalScorer),
                    "not provided by Android System: " + scorer);
            mScorer = (InternalScorer) scorer;
            checkValidRemoteId(remoteId);
            checkValidValue(value);
            final int capacity = getMaxUserDataSize();
            mRemoteIds = new ArrayList<>(capacity);
            mValues = new ArrayList<>(capacity);
            mRemoteIds.add(remoteId);
            mValues.add(value);
        }

        /**
         * Adds a new value for user data.
         *
         * @param remoteId unique string used to identify the user data.
         * @param value value of the user data.
         *
         * @throws IllegalStateException if {@link #build()} or
         * {@link #add(String, String)} with the same {@code remoteId} has already
         * been called, or if the number of values add (i.e., calls made to this method plus
         * constructor) is more than {@link UserData#getMaxUserDataSize()}.
         *
         * @throws IllegalArgumentException if {@code remoteId} or {@code value} are empty or if the
         * length of {@code value} is lower than {@link UserData#getMinValueLength()}
         * or higher than {@link UserData#getMaxValueLength()}.
         */
        public Builder add(@NonNull String remoteId, @NonNull String value) {
            throwIfDestroyed();
            checkValidRemoteId(remoteId);
            checkValidValue(value);

            Preconditions.checkState(!mRemoteIds.contains(remoteId),
                    // Don't include remoteId on message because it could contain PII
                    "already has entry with same remoteId");
            Preconditions.checkState(!mValues.contains(value),
                    // Don't include remoteId on message because it could contain PII
                    "already has entry with same value");
            Preconditions.checkState(mRemoteIds.size() < getMaxUserDataSize(),
                    "already added " + mRemoteIds.size() + " elements");
            mRemoteIds.add(remoteId);
            mValues.add(value);

            return this;
        }

        private void checkValidRemoteId(@Nullable String remoteId) {
            Preconditions.checkNotNull(remoteId);
            Preconditions.checkArgument(!remoteId.isEmpty(), "remoteId cannot be empty");
        }

        private void checkValidValue(@Nullable String value) {
            Preconditions.checkNotNull(value);
            final int length = value.length();
            Preconditions.checkArgumentInRange(length, getMinValueLength(),
                    getMaxValueLength(), "value length (" + length + ")");
        }

        /**
         * Creates a new {@link UserData} instance.
         *
         * <p>You should not interact with this builder once this method is called.
         *
         * @throws IllegalStateException if {@link #build()} was already called.
         *
         * @return The built dataset.
         */
        public UserData build() {
            throwIfDestroyed();
            mDestroyed = true;
            return new UserData(this);
        }

        private void throwIfDestroyed() {
            if (mDestroyed) {
                throw new IllegalStateException("Already called #build()");
            }
        }
    }

    /////////////////////////////////////
    // Object "contract" methods. //
    /////////////////////////////////////
    @Override
    public String toString() {
        if (!sDebug) return super.toString();

        final StringBuilder builder = new StringBuilder("UserData: [scorer=").append(mScorer);
        // Cannot disclose remote ids or values because they could contain PII
        builder.append(", remoteIds=");
        Helper.appendRedacted(builder, mRemoteIds);
        builder.append(", values=");
        Helper.appendRedacted(builder, mValues);
        return builder.append("]").toString();
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
        parcel.writeParcelable(mScorer, flags);
        parcel.writeStringArray(mRemoteIds);
        parcel.writeStringArray(mValues);
    }

    public static final Parcelable.Creator<UserData> CREATOR =
            new Parcelable.Creator<UserData>() {
        @Override
        public UserData createFromParcel(Parcel parcel) {
            // Always go through the builder to ensure the data ingested by
            // the system obeys the contract of the builder to avoid attacks
            // using specially crafted parcels.
            final InternalScorer scorer = parcel.readParcelable(null);
            final String[] remoteIds = parcel.readStringArray();
            final String[] values = parcel.readStringArray();
            final Builder builder = new Builder(scorer, remoteIds[0], values[0]);
            for (int i = 1; i < remoteIds.length; i++) {
                builder.add(remoteIds[i], values[i]);
            }
            return builder.build();
        }

        @Override
        public UserData[] newArray(int size) {
            return new UserData[size];
        }
    };

    /**
     * Gets the maximum number of values that can be added to a {@link UserData}.
     */
    public static int getMaxUserDataSize() {
        return getInt(AUTOFILL_USER_DATA_MAX_USER_DATA_SIZE, DEFAULT_MAX_USER_DATA_SIZE);
    }

    /**
     * Gets the maximum number of ids that can be passed to {@link
     * FillResponse.Builder#setFieldClassificationIds(android.view.autofill.AutofillId...)}.
     */
    public static int getMaxFieldClassificationIdsSize() {
        return getInt(AUTOFILL_USER_DATA_MAX_FIELD_CLASSIFICATION_IDS_SIZE,
            DEFAULT_MAX_FIELD_CLASSIFICATION_IDS_SIZE);
    }

    /**
     * Gets the minimum length of values passed to {@link Builder#Builder(Scorer, String, String)}.
     */
    public static int getMinValueLength() {
        return getInt(AUTOFILL_USER_DATA_MIN_VALUE_LENGTH, DEFAULT_MIN_VALUE_LENGTH);
    }

    /**
     * Gets the maximum length of values passed to {@link Builder#Builder(Scorer, String, String)}.
     */
    public static int getMaxValueLength() {
        return getInt(AUTOFILL_USER_DATA_MAX_VALUE_LENGTH, DEFAULT_MAX_VALUE_LENGTH);
    }

    private static int getInt(String settings, int defaultValue) {
        ContentResolver cr = null;
        final ActivityThread at = ActivityThread.currentActivityThread();
        if (at != null) {
            cr = at.getApplication().getContentResolver();
        }

        if (cr == null) {
            Log.w(TAG, "Could not read from " + settings + "; hardcoding " + defaultValue);
            return defaultValue;
        }
        return Settings.Secure.getInt(cr, settings, defaultValue);
    }
}
