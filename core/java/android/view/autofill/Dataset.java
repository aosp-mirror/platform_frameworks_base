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
import static android.view.autofill.Helper.append;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.assist.AssistStructure.ViewNode;
import android.hardware.fingerprint.FingerprintManager.CryptoObject;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
 * See {@link FillResponse} for examples.
 */
public final class Dataset implements Parcelable {

    private final CharSequence mName;
    private final ArrayList<DatasetField> mFields;
    private final Bundle mExtras;
    private final int mFlags;
    private final boolean mRequiresAuth;
    private final boolean mHasCryptoObject;
    private final long mCryptoOpId;

    private Dataset(Dataset.Builder builder) {
        mName = builder.mName;
        // TODO(b/33197203): make an immutable copy of mFields?
        mFields = builder.mFields;
        mExtras = builder.mExtras;
        mFlags = builder.mFlags;
        mRequiresAuth = builder.mRequiresAuth;
        mHasCryptoObject = builder.mHasCryptoObject;
        mCryptoOpId = builder.mCryptoOpId;
    }

    /** @hide */
    public CharSequence getName() {
        return mName;
    }

    /** @hide */
    public List<DatasetField> getFields() {
        return mFields;
    }

    /** @hide */
    public Bundle getExtras() {
        return mExtras;
    }

    /** @hide */
    public int getFlags() {
        return mFlags;
    }

    /** @hide */
    public boolean isAuthRequired() {
        return mRequiresAuth;
    }

    /** @hide */
    public boolean isEmpty() {
        return mFields.isEmpty();
    }

    /** @hide */
    public boolean hasCryptoObject() {
        return mHasCryptoObject;
    }

    /** @hide */
    public long getCryptoObjectOpId() {
        return mCryptoOpId;
    }

    @Override
    public String toString() {
        if (!DEBUG) return super.toString();

        final StringBuilder builder = new StringBuilder("Dataset [name=").append(mName)
                .append(", fields=").append(mFields).append(", extras=");
        append(builder, mExtras)
            .append(", flags=").append(mFlags)
            .append(", requiresAuth: ").append(mRequiresAuth)
            .append(", hasCrypto: ").append(mHasCryptoObject);
        return builder.append(']').toString();
    }

    /**
     * A builder for {@link Dataset} objects.
     */
    public static final class Builder {
        private CharSequence mName;
        private final ArrayList<DatasetField> mFields = new ArrayList<>();
        private Bundle mExtras;
        private int mFlags;
        private boolean mRequiresAuth;
        private boolean mHasCryptoObject;
        private long mCryptoOpId;

        /**
         * Creates a new builder.
         *
         * @param name Name used to identify the dataset in the UI. Typically it's the same value as
         * the first field in the dataset (like username or email address) or an user-provided name
         * (like "My Work Address").
         */
        public Builder(CharSequence name) {
            mName = Preconditions.checkStringNotEmpty(name, "name cannot be empty or null");
        }

        /**
         * Requires dataset authentication through the {@link
         * android.service.autofill.AutoFillService} before auto-filling the activity with this
         * dataset.
         *
         * <p>This method is typically called when the device (or the service) does not support
         * fingerprint authentication (and hence it cannot use {@link
         * #requiresFingerprintAuthentication(CryptoObject, Bundle, int)}) or when the service needs
         * to use a custom authentication UI for the dataset. For example, when a dataset contains
         * credit card information (such as number, expiration date, and verification code), the
         * service displays an authentication dialog asking for the verification code to unlock the
         * rest of the data).
         *
         * <p>Since the dataset is "locked" until the user authenticates it, typically this dataset
         * name is masked (for example, "VISA....1234").
         *
         * <p>When the user selects this dataset, the Android System calls {@link
         * android.service.autofill.AutoFillService#onDatasetAuthenticationRequest(Bundle, int)}
         * passing {@link android.service.autofill.AutoFillService#FLAG_AUTHENTICATION_REQUESTED} in
         * the flags and the same {@code extras} passed to this method. The service can then
         * displays its custom authentication UI, and then call the proper method on {@link
         * android.service.autofill.FillCallback} depending on the authentication result and whether
         * this dataset already contains the fields needed to auto-fill the activity:
         *
         * <ul>
         *   <li>If authentication failed, call
         *   {@link android.service.autofill.FillCallback#onDatasetAuthentication(Dataset,
         *       int)} passing {@link
         *       android.service.autofill.AutoFillService#FLAG_AUTHENTICATION_ERROR} in the flags.
         *   <li>If authentication succeeded and this datast is empty (no fields), call {@link
         *       android.service.autofill.FillCallback#onSuccess(FillResponse)} with a new dataset
         *       (with the proper fields).
         *   <li>If authentication succeeded and this response is not empty, call {@link
         *       android.service.autofill.FillCallback#onDatasetAuthentication(Dataset, int)}
         *       passing
         *       {@link android.service.autofill.AutoFillService#FLAG_AUTHENTICATION_SUCCESS} in the
         *       {@code flags} and {@code null} as the {@code dataset}.
         * </ul>
         *
         * @param extras when set, will be passed back in the {@link
         *     android.service.autofill.AutoFillService#onDatasetAuthenticationRequest(Bundle,
         *     int)}, call so it could be used by the service to handle state.
         * @param flags optional parameters, currently ignored.
         */
        public Builder requiresCustomAuthentication(@Nullable Bundle extras, int flags) {
            return requiresAuthentication(null, extras, flags);
        }

        /**
         * Requires dataset authentication through the Fingerprint sensor before auto-filling the
         * activity with this dataset.
         *
         * <p>This method is typically called when the dataset contains sensitive information (for
         * example, credit card information) and the provider requires the user to re-authenticate
         * before using it.
         *
         * <p>Since the dataset is "locked" until the user authenticates it, typically this dataset
         * name is masked (for example, "VISA....1234").
         *
         * <p>When the user selects this dataset, the Android System displays an UI affordance
         * asking the user to use the fingerprint sensor unlock the dataset, and what happens after
         * a successful fingerprint authentication depends on whether the dataset is empty (no
         * fields, only the masked name) or not:
         *
         * <ul>
         *   <li>If it's empty, the Android System will call {@link
         *       android.service.autofill.AutoFillService#onDatasetAuthenticationRequest(Bundle,
         *       int)} passing {@link
         *       android.service.autofill.AutoFillService#FLAG_AUTHENTICATION_SUCCESS}} in the
         *       flags.
         *   <li>If it's not empty, the activity will be auto-filled with its data.
         * </ul>
         *
         * <p>If the fingerprint authentication fails, the Android System will call {@link
         * android.service.autofill.AutoFillService#onDatasetAuthenticationRequest(Bundle, int)}
         * passing {@link android.service.autofill.AutoFillService#FLAG_AUTHENTICATION_ERROR} in the
         * flags.
         *
         * <p><strong>NOTE: </note> the {@link android.service.autofill.AutoFillService} should use
         * the {@link android.hardware.fingerprint.FingerprintManager} to check if fingerpint
         * authentication is available before using this method, and use other alternatives (such as
         * {@link #requiresCustomAuthentication(Bundle, int)}) if it is not: if this method is
         * called when fingerprint is not available, Android System will call {@link
         * android.service.autofill.AutoFillService#onDatasetAuthenticationRequest(Bundle, int)}
         * passing {@link
         * android.service.autofill.AutoFillService#FLAG_FINGERPRINT_AUTHENTICATION_NOT_AVAILABLE}
         * in the flags, but it would be wasting system resources (and worsening the user
         * experience) in the process.
         *
         * @param crypto object that will be authenticated.
         * @param extras when set, will be passed back in the {@link
         *     android.service.autofill.AutoFillService#onDatasetAuthenticationRequest(Bundle, int)}
         *     call so it could be used by the service to handle state.
         * @param flags optional parameters, currently ignored.
         */
        public Builder requiresFingerprintAuthentication(CryptoObject crypto,
                @Nullable Bundle extras, int flags) {
            // TODO(b/33197203): should we allow crypto to be null?
            Preconditions.checkArgument(crypto != null, "must pass a CryptoObject");
            return requiresAuthentication(crypto, extras, flags);
        }

        private Builder requiresAuthentication(CryptoObject cryptoObject, Bundle extras,
                int flags) {
            // There can be only one!
            Preconditions.checkState(!mRequiresAuth,
                    "requires-authentication methods already called");
            // TODO(b/33197203): make sure that either this method or setExtras() is called, but
            // not both
            mExtras = extras;
            mFlags = flags;
            mRequiresAuth = true;
            if (cryptoObject != null) {
                mHasCryptoObject = true;
                mCryptoOpId = cryptoObject.getOpId();
            }
            return this;
        }

        /**
         * Sets the value of a field.
         *
         * @param id id returned by {@link ViewNode#getAutoFillId()}.
         * @param value value to be auto filled.
         */
        public Dataset.Builder setValue(AutoFillId id, AutoFillValue value) {
            putField(new DatasetField(id, value));
            return this;
        }

        /**
         * Creates a new {@link Dataset} instance.
         */
        public Dataset build() {
            return new Dataset(this);
        }

        /**
         * Sets a {@link Bundle} that will be passed to subsequent calls to
         * {@link android.service.autofill.AutoFillService} methods such as
 * {@link android.service.autofill.AutoFillService#onSaveRequest(android.app.assist.AssistStructure,
         * Bundle, android.os.CancellationSignal, android.service.autofill.SaveCallback)}, using
         * {@link android.service.autofill.AutoFillService#EXTRA_DATASET_EXTRAS} as the key.
         *
         * <p>It can be used to keep service state in between calls.
         */
        public Builder setExtras(Bundle extras) {
            // TODO(b/33197203): make sure that either this method or the requires-Authentication
            // ones are called, but not both
            mExtras = Objects.requireNonNull(extras, "extras cannot be null");
            return this;
        }

        /**
         * Emulates {@code Map.put()} by adding a new field to the list if its id is not the yet,
         * or replacing the existing one.
         */
        private void putField(DatasetField field) {
            // TODO(b/33197203): check if already exists and replaces it if so
            mFields.add(field);
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
        parcel.writeList(mFields);
        parcel.writeBundle(mExtras);
        parcel.writeInt(mFlags);
        parcel.writeInt(mRequiresAuth ? 1 : 0);
        parcel.writeInt(mHasCryptoObject ? 1 : 0);
        if (mHasCryptoObject) {
            parcel.writeLong(mCryptoOpId);
        }
    }

    @SuppressWarnings("unchecked")
    private Dataset(Parcel parcel) {
        mName = parcel.readCharSequence();
        mFields = parcel.readArrayList(null);
        mExtras = parcel.readBundle();
        mFlags = parcel.readInt();
        mRequiresAuth = parcel.readInt() == 1;
        mHasCryptoObject = parcel.readInt() == 1;
        mCryptoOpId = mHasCryptoObject ? parcel.readLong() : 0;
    }

    public static final Parcelable.Creator<Dataset> CREATOR = new Parcelable.Creator<Dataset>() {
        @Override
        public Dataset createFromParcel(Parcel source) {
            return new Dataset(source);
        }

        @Override
        public Dataset[] newArray(int size) {
            return new Dataset[size];
        }
    };
}
