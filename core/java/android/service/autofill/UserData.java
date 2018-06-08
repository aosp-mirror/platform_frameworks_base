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

import static android.provider.Settings.Secure.AUTOFILL_USER_DATA_MAX_CATEGORY_COUNT;
import static android.provider.Settings.Secure.AUTOFILL_USER_DATA_MAX_FIELD_CLASSIFICATION_IDS_SIZE;
import static android.provider.Settings.Secure.AUTOFILL_USER_DATA_MAX_USER_DATA_SIZE;
import static android.provider.Settings.Secure.AUTOFILL_USER_DATA_MAX_VALUE_LENGTH;
import static android.provider.Settings.Secure.AUTOFILL_USER_DATA_MIN_VALUE_LENGTH;
import static android.view.autofill.Helper.sDebug;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread;
import android.content.ContentResolver;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.provider.Settings;
import android.service.autofill.FieldClassification.Match;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;
import android.view.autofill.AutofillManager;
import android.view.autofill.Helper;

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Defines the user data used for
 * <a href="AutofillService.html#FieldClassification">field classification</a>.
 */
public final class UserData implements Parcelable {

    private static final String TAG = "UserData";

    private static final int DEFAULT_MAX_USER_DATA_SIZE = 50;
    private static final int DEFAULT_MAX_CATEGORY_COUNT = 10;
    private static final int DEFAULT_MAX_FIELD_CLASSIFICATION_IDS_SIZE = 10;
    private static final int DEFAULT_MIN_VALUE_LENGTH = 3;
    private static final int DEFAULT_MAX_VALUE_LENGTH = 100;

    private final String mId;
    private final String mAlgorithm;
    private final Bundle mAlgorithmArgs;
    private final String[] mCategoryIds;
    private final String[] mValues;

    private UserData(Builder builder) {
        mId = builder.mId;
        mAlgorithm = builder.mAlgorithm;
        mAlgorithmArgs = builder.mAlgorithmArgs;
        mCategoryIds = new String[builder.mCategoryIds.size()];
        builder.mCategoryIds.toArray(mCategoryIds);
        mValues = new String[builder.mValues.size()];
        builder.mValues.toArray(mValues);
    }

    /**
     * Gets the name of the algorithm that is used to calculate
     * {@link Match#getScore() match scores}.
     */
    @Nullable
    public String getFieldClassificationAlgorithm() {
        return mAlgorithm;
    }

    /**
     * Gets the id.
     */
    public String getId() {
        return mId;
    }

    /** @hide */
    public Bundle getAlgorithmArgs() {
        return mAlgorithmArgs;
    }

    /** @hide */
    public String[] getCategoryIds() {
        return mCategoryIds;
    }

    /** @hide */
    public String[] getValues() {
        return mValues;
    }

    /** @hide */
    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.print("id: "); pw.print(mId);
        pw.print(prefix); pw.print("Algorithm: "); pw.print(mAlgorithm);
        pw.print(" Args: "); pw.println(mAlgorithmArgs);

        // Cannot disclose field ids or values because they could contain PII
        pw.print(prefix); pw.print("Field ids size: "); pw.println(mCategoryIds.length);
        for (int i = 0; i < mCategoryIds.length; i++) {
            pw.print(prefix); pw.print(prefix); pw.print(i); pw.print(": ");
            pw.println(Helper.getRedacted(mCategoryIds[i]));
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
        pw.print(prefix); pw.print("maxCategoryCount: "); pw.println(getMaxCategoryCount());
        pw.print(prefix); pw.print("minValueLength: "); pw.println(getMinValueLength());
        pw.print(prefix); pw.print("maxValueLength: "); pw.println(getMaxValueLength());
    }

    /**
     * A builder for {@link UserData} objects.
     */
    public static final class Builder {
        private final String mId;
        private final ArrayList<String> mCategoryIds;
        private final ArrayList<String> mValues;
        private String mAlgorithm;
        private Bundle mAlgorithmArgs;
        private boolean mDestroyed;

        // Non-persistent array used to limit the number of unique ids.
        private final ArraySet<String> mUniqueCategoryIds;

        /**
         * Creates a new builder for the user data used for <a href="#FieldClassification">field
         * classification</a>.
         *
         * <p>The user data must contain at least one pair of {@code value} -> {@code categoryId},
         * and more pairs can be added through the {@link #add(String, String)} method. For example:
         *
         * <pre class="prettyprint">
         * new UserData.Builder("v1", "Bart Simpson", "name")
         *   .add("bart.simpson@example.com", "email")
         *   .add("el_barto@example.com", "email")
         *   .build();
         * </pre>
         *
         * @param id id used to identify the whole {@link UserData} object. This id is also returned
         * by {@link AutofillManager#getUserDataId()}, which can be used to check if the
         * {@link UserData} is up-to-date without fetching the whole object (through
         * {@link AutofillManager#getUserData()}).
         *
         * @param value value of the user data.
         * @param categoryId string used to identify the category the value is associated with.
         *
         * @throws IllegalArgumentException if any of the following occurs:
         * <ul>
         *   <li>{@code id} is empty</li>
         *   <li>{@code categoryId} is empty</li>
         *   <li>{@code value} is empty</li>
         *   <li>the length of {@code value} is lower than {@link UserData#getMinValueLength()}</li>
         *   <li>the length of {@code value} is higher than
         *       {@link UserData#getMaxValueLength()}</li>
         * </ul>
         */
        public Builder(@NonNull String id, @NonNull String value, @NonNull String categoryId) {
            mId = checkNotEmpty("id", id);
            checkNotEmpty("categoryId", categoryId);
            checkValidValue(value);
            final int maxUserDataSize = getMaxUserDataSize();
            mCategoryIds = new ArrayList<>(maxUserDataSize);
            mValues = new ArrayList<>(maxUserDataSize);
            mUniqueCategoryIds = new ArraySet<>(getMaxCategoryCount());

            addMapping(value, categoryId);
        }

        /**
         * Sets the algorithm used for <a href="#FieldClassification">field classification</a>.
         *
         * <p>The currently available algorithms can be retrieve through
         * {@link AutofillManager#getAvailableFieldClassificationAlgorithms()}.
         *
         * <p>If not set, the
         * {@link AutofillManager#getDefaultFieldClassificationAlgorithm() default algorithm} is
         * used instead.
         *
         * @param name name of the algorithm or {@code null} to used default.
         * @param args optional arguments to the algorithm.
         *
         * @return this builder
         */
        public Builder setFieldClassificationAlgorithm(@Nullable String name,
                @Nullable Bundle args) {
            throwIfDestroyed();
            mAlgorithm = name;
            mAlgorithmArgs = args;
            return this;
        }

        /**
         * Adds a new value for user data.
         *
         * @param value value of the user data.
         * @param categoryId string used to identify the category the value is associated with.
         *
         * @throws IllegalStateException if:
         * <ul>
         *   <li>{@link #build()} already called</li>
         *   <li>the {@code value} has already been added</li>
         *   <li>the number of unique {@code categoryId} values added so far is more than
         *       {@link UserData#getMaxCategoryCount()}</li>
         *   <li>the number of {@code values} added so far is is more than
         *       {@link UserData#getMaxUserDataSize()}</li>
         * </ul>
         *
         * @throws IllegalArgumentException if any of the following occurs:
         * <ul>
         *   <li>{@code id} is empty</li>
         *   <li>{@code categoryId} is empty</li>
         *   <li>{@code value} is empty</li>
         *   <li>the length of {@code value} is lower than {@link UserData#getMinValueLength()}</li>
         *   <li>the length of {@code value} is higher than
         *       {@link UserData#getMaxValueLength()}</li>
         * </ul>
         */
        public Builder add(@NonNull String value, @NonNull String categoryId) {
            throwIfDestroyed();
            checkNotEmpty("categoryId", categoryId);
            checkValidValue(value);

            if (!mUniqueCategoryIds.contains(categoryId)) {
                // New category - check size
                Preconditions.checkState(mUniqueCategoryIds.size() < getMaxCategoryCount(),
                        "already added " + mUniqueCategoryIds.size() + " unique category ids");

            }

            Preconditions.checkState(!mValues.contains(value),
                    // Don't include value on message because it could contain PII
                    "already has entry with same value");
            Preconditions.checkState(mValues.size() < getMaxUserDataSize(),
                    "already added " + mValues.size() + " elements");
            addMapping(value, categoryId);

            return this;
        }

        private void addMapping(@NonNull String value, @NonNull String categoryId) {
            mCategoryIds.add(categoryId);
            mValues.add(value);
            mUniqueCategoryIds.add(categoryId);
        }

        private String checkNotEmpty(@NonNull String name, @Nullable String value) {
            Preconditions.checkNotNull(value);
            Preconditions.checkArgument(!TextUtils.isEmpty(value), "%s cannot be empty", name);
            return value;
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

        final StringBuilder builder = new StringBuilder("UserData: [id=").append(mId)
                .append(", algorithm=").append(mAlgorithm);
        // Cannot disclose category ids or values because they could contain PII
        builder.append(", categoryIds=");
        Helper.appendRedacted(builder, mCategoryIds);
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
        parcel.writeString(mId);
        parcel.writeStringArray(mCategoryIds);
        parcel.writeStringArray(mValues);
        parcel.writeString(mAlgorithm);
        parcel.writeBundle(mAlgorithmArgs);
    }

    public static final Parcelable.Creator<UserData> CREATOR =
            new Parcelable.Creator<UserData>() {
        @Override
        public UserData createFromParcel(Parcel parcel) {
            // Always go through the builder to ensure the data ingested by
            // the system obeys the contract of the builder to avoid attacks
            // using specially crafted parcels.
            final String id = parcel.readString();
            final String[] categoryIds = parcel.readStringArray();
            final String[] values = parcel.readStringArray();
            final Builder builder = new Builder(id, values[0], categoryIds[0])
                    .setFieldClassificationAlgorithm(parcel.readString(), parcel.readBundle());
            for (int i = 1; i < categoryIds.length; i++) {
                builder.add(values[i], categoryIds[i]);
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
     * Gets the maximum number of unique category ids that can be passed to
     * the builder's constructor and {@link Builder#add(String, String)}.
     */
    public static int getMaxCategoryCount() {
        return getInt(AUTOFILL_USER_DATA_MAX_CATEGORY_COUNT, DEFAULT_MAX_CATEGORY_COUNT);
    }

    /**
     * Gets the minimum length of values passed to the builder's constructor or
     * or {@link Builder#add(String, String)}.
     */
    public static int getMinValueLength() {
        return getInt(AUTOFILL_USER_DATA_MIN_VALUE_LENGTH, DEFAULT_MIN_VALUE_LENGTH);
    }

    /**
     * Gets the maximum length of values passed to the builder's constructor or
     * or {@link Builder#add(String, String)}.
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
