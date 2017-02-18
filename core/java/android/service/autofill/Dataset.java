/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.view.autofill.Helper.DEBUG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.IntentSender;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.autofill.AutoFillId;
import android.view.autofill.AutoFillValue;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;

/**
 * A set of data that can be used to auto-fill an {@link android.app.Activity}.
 *
 * <p>It contains:
 *
 * <ol>
 *   <li>A name used to identify the dataset in the UI.
 *   <li>A list of id/value pairs for the fields that can be auto-filled.
 *   <li>A list of savable ids in addition to the ones with a provided value.
 * </ol>
 *
 * @see android.service.autofill.FillResponse for examples.
 */
public final class Dataset implements Parcelable {

    private final CharSequence mName;
    private final ArrayList<AutoFillId> mFieldIds;
    private final ArrayList<AutoFillValue> mFieldValues;
    private final IntentSender mAuthentication;

    private Dataset(Builder builder) {
        mName = builder.mName;
        mFieldIds = builder.mFieldIds;
        mFieldValues = builder.mFieldValues;
        mAuthentication = builder.mAuthentication;
    }

    /** @hide */
    public @NonNull CharSequence getName() {
        return mName;
    }

    /** @hide */
    public @Nullable ArrayList<AutoFillId> getFieldIds() {
        return mFieldIds;
    }

    /** @hide */
    public @Nullable ArrayList<AutoFillValue> getFieldValues() {
        return mFieldValues;
    }

    /** @hide */
    public @Nullable IntentSender getAuthentication() {
        return mAuthentication;
    }

    /** @hide */
    public boolean isEmpty() {
        return mFieldIds == null || mFieldIds.isEmpty();
    }

    @Override
    public String toString() {
        if (!DEBUG) return super.toString();

        final StringBuilder builder = new StringBuilder("Dataset [name=").append(mName)
                .append(", fieldIds=").append(mFieldIds)
                .append(", fieldValues=").append(mFieldValues)
                .append(", hasAuthentication=").append(mAuthentication != null);
        return builder.append(']').toString();
    }

    /**
     * A builder for {@link Dataset} objects. You must to provide at least
     * one value for a field or set an authentication intent.
     */
    public static final class Builder {
        private CharSequence mName;
        private ArrayList<AutoFillId> mFieldIds;
        private ArrayList<AutoFillValue> mFieldValues;
        private IntentSender mAuthentication;
        private boolean mDestroyed;

        /**
         * Creates a new builder.
         *
         * @param name Name used to identify the dataset in the UI. Typically it's the same value as
         * the first field in the dataset (like username or email address) or a user-provided name
         * (like "My Work Address").
         */
        public Builder(@NonNull CharSequence name) {
            mName = Preconditions.checkStringNotEmpty(name, "name cannot be empty or null");
        }

        /**
         * Requires a dataset authentication before auto-filling the activity with this dataset.
         *
         * <p>This method is called when you need to provide an authentication
         * UI for the data set. For example, when a data set contains credit card information
         * (such as number, expiration date, and verification code), you can display UI
         * asking for the verification code to before filing in the data). Even if the
         * data set is completely populated the system will launch the specified authentication
         * intent and will need your approval to fill it in. Since the data set is "locked"
         * until the user authenticates it, typically this data set name is masked
         * (for example, "VISA....1234"). Typically you would want to store the data set
         * labels non-encrypted and the actual sensitive data encrypted and not in memory.
         * This allows showing the labels in the UI while involving the user if one of
         * the items with these labels is chosen. Note that if you use sensitive data as
         * a label, for example an email address, then it should also be encrypted.</p>
         *
         * <p>When a user triggers auto-fill, the system launches the provided intent
         * whose extras will have the {@link
         * android.view.autofill.AutoFillManager#EXTRA_ASSIST_STRUCTURE screen content}. Once
         * you complete your authentication flow you should set the activity result to {@link
         * android.app.Activity#RESULT_OK} and provide the fully populated {@link Dataset
         * dataset} by setting it to the {@link
         * android.view.autofill.AutoFillManager#EXTRA_AUTHENTICATION_RESULT} extra. For example,
         * if you provided an credit card information without the CVV for the data set in the
         * {@link FillResponse response} then the returned data set should contain the
         * CVV entry.</p>
         *
         * <p></><strong>Note:</strong> Do not make the provided pending intent
         * immutable by using {@link android.app.PendingIntent#FLAG_IMMUTABLE} as the
         * platform needs to fill in the authentication arguments.</p>
         *
         * @param authentication Intent to an activity with your authentication flow.
         *
         * @see android.app.PendingIntent
         */
        public @NonNull Builder setAuthentication(@Nullable IntentSender authentication) {
            throwIfDestroyed();
            mAuthentication = authentication;
            return this;
        }

        /**
         * Sets the value of a field.
         *
         * @param id id returned by {@link
         *         android.app.assist.AssistStructure.ViewNode#getAutoFillId()}.
         * @param value value to be auto filled.
         */
        public @NonNull Builder setValue(@NonNull AutoFillId id, @NonNull AutoFillValue value) {
            throwIfDestroyed();
            Preconditions.checkNotNull(id, "id cannot be null");
            Preconditions.checkNotNull(value, "value cannot be null");
            if (mFieldIds != null) {
                final int existingIdx = mFieldIds.indexOf(id);
                if (existingIdx >= 0) {
                    mFieldValues.set(existingIdx, value);
                    return this;
                }
            } else {
                mFieldIds = new ArrayList<>();
                mFieldValues = new ArrayList<>();
            }
            mFieldIds.add(id);
            mFieldValues.add(value);
            return this;
        }

        /**
         * Creates a new {@link Dataset} instance. You should not interact
         * with this builder once this method is called.
         */
        public @NonNull Dataset build() {
            throwIfDestroyed();
            mDestroyed = true;
            if (mFieldIds == null && mAuthentication == null) {
                throw new IllegalArgumentException(
                        "at least one value or an authentication must be set");
            }
            return new Dataset(this);
        }

        private void throwIfDestroyed() {
            if (mDestroyed) {
                throw new IllegalStateException("Already called #build()");
            }
        }
    }

    /////////////////////////////////////
    //  Parcelable "contract" methods. //
    /////////////////////////////////////

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeCharSequence(mName);
        parcel.writeTypedArrayList(mFieldIds, 0);
        parcel.writeTypedArrayList(mFieldValues, 0);
        parcel.writeParcelable(mAuthentication, flags);
    }

    public static final Creator<Dataset> CREATOR = new Creator<Dataset>() {
        @Override
        public Dataset createFromParcel(Parcel parcel) {
            // Always go through the builder to ensure the data ingested by
            // the system obeys the contract of the builder to avoid attacks
            // using specially crafted parcels.
            final Builder builder = new Builder(parcel.readCharSequence());
            final ArrayList<AutoFillId> ids = parcel.readTypedArrayList(null);
            final ArrayList<AutoFillValue> values = parcel.readTypedArrayList(null);
            final int idCount = (ids != null) ? ids.size() : 0;
            final int valueCount = (values != null) ? values.size() : 0;
            for (int i = 0; i < idCount; i++) {
                AutoFillId id = ids.get(i);
                AutoFillValue value = (valueCount > i) ? values.get(i) : null;
                builder.setValue(id, value);
            }
            builder.setAuthentication(parcel.readParcelable(null));
            return builder.build();
        }

        @Override
        public Dataset[] newArray(int size) {
            return new Dataset[size];
        }
    };
}
