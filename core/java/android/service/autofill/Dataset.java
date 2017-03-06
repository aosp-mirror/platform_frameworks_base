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
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;

/**
 * A set of data that can be used to autofill an {@link android.app.Activity}.
 *
 * <p>It contains:
 *
 * <ol>
 *   <li>A list of values for input fields.
 *   <li>A presentation view to visualize.
 *   <li>An optional intent to authenticate.
 * </ol>
 *
 * @see android.service.autofill.FillResponse for examples.
 */
public final class Dataset implements Parcelable {

    private final ArrayList<AutofillId> mFieldIds;
    private final ArrayList<AutofillValue> mFieldValues;
    private final RemoteViews mPresentation;
    private final IntentSender mAuthentication;

    private Dataset(Builder builder) {
        mFieldIds = builder.mFieldIds;
        mFieldValues = builder.mFieldValues;
        mPresentation = builder.mPresentation;
        mAuthentication = builder.mAuthentication;
    }

    /** @hide */
    public @Nullable ArrayList<AutofillId> getFieldIds() {
        return mFieldIds;
    }

    /** @hide */
    public @Nullable ArrayList<AutofillValue> getFieldValues() {
        return mFieldValues;
    }

    /** @hide */
    public @Nullable RemoteViews getPresentation() {
        return mPresentation;
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

        return new StringBuilder("Dataset [")
                .append(", fieldIds=").append(mFieldIds)
                .append(", fieldValues=").append(mFieldValues)
                .append(", hasPresentation=").append(mPresentation != null)
                .append(", hasAuthentication=").append(mAuthentication != null)
                .append(']').toString();
    }

    /**
     * A builder for {@link Dataset} objects. You must to provide at least
     * one value for a field or set an authentication intent.
     */
    public static final class Builder {
        private ArrayList<AutofillId> mFieldIds;
        private ArrayList<AutofillValue> mFieldValues;
        private RemoteViews mPresentation;
        private IntentSender mAuthentication;
        private boolean mDestroyed;

        /**
         * Creates a new builder.
         *
         * @param presentation The presentation used to visualize this dataset.
         */
        public Builder(@NonNull RemoteViews presentation) {
            Preconditions.checkNotNull(presentation, "presentation must be non-null");
            mPresentation = presentation;
        }

        /**
         * Requires a dataset authentication before autofilling the activity with this dataset.
         *
         * <p>This method is called when you need to provide an authentication
         * UI for the data set. For example, when a data set contains credit card information
         * (such as number, expiration date, and verification code), you can display UI
         * asking for the verification code before filing in the data. Even if the
         * data set is completely populated the system will launch the specified authentication
         * intent and will need your approval to fill it in. Since the data set is "locked"
         * until the user authenticates it, typically this data set name is masked
         * (for example, "VISA....1234"). Typically you would want to store the data set
         * labels non-encrypted and the actual sensitive data encrypted and not in memory.
         * This allows showing the labels in the UI while involving the user if one of
         * the items with these labels is chosen. Note that if you use sensitive data as
         * a label, for example an email address, then it should also be encrypted.</p>
         *
         * <p>When a user triggers autofill, the system launches the provided intent
         * whose extras will have the {@link
         * android.view.autofill.AutofillManager#EXTRA_ASSIST_STRUCTURE screen content}. Once
         * you complete your authentication flow you should set the activity result to {@link
         * android.app.Activity#RESULT_OK} and provide the fully populated {@link Dataset
         * dataset} by setting it to the {@link
         * android.view.autofill.AutofillManager#EXTRA_AUTHENTICATION_RESULT} extra. For example,
         * if you provided credit card information without the CVV for the data set in the
         * {@link FillResponse response} then the returned data set should contain the
         * CVV entry.</p>
         *
         * <p></><strong>Note:</strong> Do not make the provided pending intent
         * immutable by using {@link android.app.PendingIntent#FLAG_IMMUTABLE} as the
         * platform needs to fill in the authentication arguments.</p>
         *
         * @param authentication Intent to an activity with your authentication flow.
         * @return This builder.
         *
         * @see android.app.PendingIntent
         */
        public @NonNull Builder setAuthentication(@Nullable IntentSender authentication) {
            throwIfDestroyed();
            mAuthentication = authentication;
            return this;
        }

        /**
         * @hide
         * @deprecated TODO(b/35956626): remove once clients use other setValue()
         */
       @Deprecated
        public @NonNull Builder setValue(@NonNull AutoFillId id, @NonNull AutoFillValue value) {
            return setValue(id.getDaRealId(), value.getDaRealValue());
        }
        /**
         * Sets the value of a field.
         *
         * @param id id returned by {@link
         *         android.app.assist.AssistStructure.ViewNode#getAutofillId()}.
         * @param value value to be auto filled.
         * @return This builder.
         */
        public @NonNull Builder setValue(@NonNull AutofillId id, @NonNull AutofillValue value) {
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
         * with this builder once this method is called. It is required
         * that you specified at least one field. Also it is mandatory to
         * provide a presentation view to visualize the data set in the UI.
         *
         * @return The built dataset.
         */
        public @NonNull Dataset build() {
            throwIfDestroyed();
            mDestroyed = true;
            if (mFieldIds == null) {
                throw new IllegalArgumentException(
                        "at least one value must be set");
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
        parcel.writeParcelable(mPresentation, flags);
        parcel.writeTypedArrayList(mFieldIds, flags);
        parcel.writeTypedArrayList(mFieldValues, flags);
        parcel.writeParcelable(mAuthentication, flags);
    }

    public static final Creator<Dataset> CREATOR = new Creator<Dataset>() {
        @Override
        public Dataset createFromParcel(Parcel parcel) {
            // Always go through the builder to ensure the data ingested by
            // the system obeys the contract of the builder to avoid attacks
            // using specially crafted parcels.
            final Builder builder = new Builder(parcel.readParcelable(null));
            final ArrayList<AutofillId> ids = parcel.readTypedArrayList(null);
            final ArrayList<AutofillValue> values = parcel.readTypedArrayList(null);
            final int idCount = (ids != null) ? ids.size() : 0;
            final int valueCount = (values != null) ? values.size() : 0;
            for (int i = 0; i < idCount; i++) {
                final AutofillId id = ids.get(i);
                final AutofillValue value = (valueCount > i) ? values.get(i) : null;
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
