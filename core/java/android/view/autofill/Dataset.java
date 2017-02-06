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

package android.view.autofill;

import static android.view.autofill.Helper.DEBUG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.assist.AssistStructure.ViewNode;
import android.content.IntentSender;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;

/**
 * A set of data that can be used to auto-fill an {@link Activity}.
 *
 * <p>It contains:
 *
 * <ol>
 *   <li>A name used to identify the dataset in the UI.
 *   <li>A list of id/value pairs for the fields that can be auto-filled.
 *   <li>An optional {@link Bundle} with extras (used only by the service creating it).
 * </ol>
 *
 * @see FillResponse for examples.
 */
public final class Dataset implements Parcelable {
    private final String mId;
    private final CharSequence mName;
    private final ArrayList<AutoFillId> mFieldIds;
    private final ArrayList<AutoFillValue> mFieldValues;
    private final Bundle mExtras;
    private final IntentSender mAuthentication;

    private Dataset(Builder builder) {
        mId = builder.mId;
        mName = builder.mName;
        mFieldIds = builder.mFieldIds;
        mFieldValues = builder.mFieldValues;
        mExtras = builder.mExtras;
        mAuthentication = builder.mAuthentication;
    }

    /** @hide */
    public @NonNull String getId() {
        return mId;
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
    public @Nullable Bundle getExtras() {
        return mExtras;
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

        final StringBuilder builder = new StringBuilder("Dataset [id=").append(mId)
                .append(", name=").append(mName)
                .append(", fieldIds=").append(mFieldIds)
                .append(", fieldValues=").append(mFieldValues)
                .append(", hasAuthentication=").append(mAuthentication != null)
                .append(", hasExtras=").append(mExtras != null);
        return builder.append(']').toString();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final Dataset other = (Dataset) obj;
        if (mId == null) {
            if (other.mId != null) {
                return false;
            }
        } else if (!mId.equals(other.mId)) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return mId != null ? mId.hashCode() : 0;
    }

    /**
     * A builder for {@link Dataset} objects. You must to provide at least
     * one value for a field or set an authentication intent.
     */
    public static final class Builder {
        private String mId;
        private CharSequence mName;
        private ArrayList<AutoFillId> mFieldIds;
        private ArrayList<AutoFillValue> mFieldValues;
        private Bundle mExtras;
        private IntentSender mAuthentication;
        private boolean mDestroyed;

        /** @hide */
        // TODO(b/33197203): Remove once GCore migrates
        public Builder(@NonNull CharSequence name) {
            this(String.valueOf(System.currentTimeMillis()), name);
        }

        /**
         * Creates a new builder.
         *
         * @param id A required id to identify this dataset for future interactions related to it.
         * @param name Name used to identify the dataset in the UI. Typically it's the same value as
         * the first field in the dataset (like username or email address) or a user-provided name
         * (like "My Work Address").
         */
        public Builder(@NonNull String id, @NonNull CharSequence name) {
            mId = Preconditions.checkStringNotEmpty(id, "id cannot be empty or null");
            mName = Preconditions.checkStringNotEmpty(name, "name cannot be empty or null");
        }

        /**
         * Requires a dataset authentication before auto-filling the activity with this dataset.
         *
         * <p>This method is called when you need to provide an authentication
         * UI for the dataset. For example, when a dataset contains credit card information
         * (such as number, expiration date, and verification code), you can display UI
         * asking for the verification code to before filing in the data). Even if the
         * dataset is completely populated the system will launch the specified authentication
         * intent and will need your approval to fill it in. Since the dataset is "locked"
         * until the user authenticates it, typically this dataset name is masked
         * (for example, "VISA....1234"). Typically you would want to store the dataset
         * labels non-encypted and the actual sensitive data encrypted and not in memory.
         * This allows showing the labels in the UI while involving the user if one of
         * the items with these labels is chosen. Note that if you use sensitive data as
         * a label, for example an email address, then it should also be encrypted.
         *</p>
         *
         * <p>When a user selects this dataset, the system triggers the provided intent
         * whose extras will have the {@link android.content.Intent#EXTRA_AUTO_FILL_ITEM_ID id}
         * of the {@link android.view.autofill.Dataset dataset} to authenticate, the {@link
         * android.content.Intent#EXTRA_AUTO_FILL_EXTRAS extras} associated with this
         * dataset, and a {@link android.content.Intent#EXTRA_AUTO_FILL_CALLBACK callback}
         * to dispatch the authentication result.</p>
         *
         * <p>Once you complete your authentication flow you should use the provided callback
         * to notify for a failure or a success. In case of a success you need to provide
         * only the fully populated dataset that is being authenticated. For example, if you
         * provided a {@link FillResponse} with two {@link Dataset}s and marked that
         * only the first dataset needs an authentication then in the provided response
         * you need to provide only the fully populated dataset being authenticated instead
         * of both of them.
         * </p>
         *
         * <p>The indent sender mechanism allows you to have your authentication UI
         * implemented as an activity or a service or a receiver. However, the recommended
         * way is to do this is with an activity which the system will start in the
         * filled activity's task meaning it will properly work with back, recent apps, and
         * free-form multi-window, while avoiding the need for the "draw on top of other"
         * apps special permission. You can still theme your authentication activity's
         * UI to look like a dialog if desired.</p>
         *
         * <p></><strong>Note:</strong> Do not make the provided intent sender
         * immutable by using {@link android.app.PendingIntent#FLAG_IMMUTABLE} as the
         * platform needs to fill in the authentication arguments.</p>
         *
         * @param authentication Intent to trigger your authentication flow.
         *
         * @see android.app.PendingIntent#getIntentSender()
         */
        public @NonNull Builder setAuthentication(@Nullable IntentSender authentication) {
            throwIfDestroyed();
            mAuthentication = authentication;
            return this;
        }

        /**
         * Sets the value of a field.
         *
         * @param id id returned by {@link ViewNode#getAutoFillId()}.
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
         * Sets a {@link Bundle} that will be passed to subsequent APIs that
         * manipulate this dataset. For example, they are passed in as {@link
         * android.content.Intent#EXTRA_AUTO_FILL_EXTRAS extras} to your
         * authentication flow.
         */
        public @NonNull Builder setExtras(@Nullable Bundle extras) {
            throwIfDestroyed();
            mExtras = extras;
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
        parcel.writeString(mId);
        parcel.writeCharSequence(mName);
        parcel.writeTypedArrayList(mFieldIds, 0);
        parcel.writeTypedArrayList(mFieldValues, 0);
        parcel.writeBundle(mExtras);
        parcel.writeParcelable(mAuthentication, flags);
    }

    public static final Parcelable.Creator<Dataset> CREATOR = new Parcelable.Creator<Dataset>() {
        @Override
        public Dataset createFromParcel(Parcel parcel) {
            // Always go through the builder to ensure the data ingested by
            // the system obeys the contract of the builder to avoid attacks
            // using specially crafted parcels.
            final Builder builder = new Builder(parcel.readString(), parcel.readCharSequence());
            final ArrayList<AutoFillId> ids = parcel.readTypedArrayList(null);
            final ArrayList<AutoFillValue> values = parcel.readTypedArrayList(null);
            final int idCount = (ids != null) ? ids.size() : 0;
            final int valueCount = (values != null) ? values.size() : 0;
            for (int i = 0; i < idCount; i++) {
                AutoFillId id = ids.get(i);
                AutoFillValue value = (valueCount > i) ? values.get(i) : null;
                builder.setValue(id, value);
            }
            builder.setExtras(parcel.readBundle());
            builder.setAuthentication(parcel.readParcelable(null));
            return builder.build();
        }

        @Override
        public Dataset[] newArray(int size) {
            return new Dataset[size];
        }
    };
}
