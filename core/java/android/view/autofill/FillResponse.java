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
import android.hardware.fingerprint.FingerprintManager.CryptoObject;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.autofill.FillCallback;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Response for a {@link
 * android.service.autofill.AutoFillService#onFillRequest(android.app.assist.AssistStructure,
 * Bundle, android.os.CancellationSignal, android.service.autofill.FillCallback)} request.
 *
 * <p>The response typically contains one or more {@link Dataset}s, each representing a set of
 * fields that can be auto-filled together, and the Android System displays a dataset picker UI
 * affordance that the user must use before the {@link Activity} is filled with the dataset.
 *
 * <p>For example, for a login page with username/password where the user only has one account in
 * the service, the response could be:
 *
 * <pre class="prettyprint">
 *  new FillResponse.Builder()
 *      .add(new Dataset.Builder("homer")
 *          .setTextFieldValue(id1, "homer")
 *          .setTextFieldValue(id2, "D'OH!")
 *          .build())
 *      .build();
 * </pre>
 *
 * <p>If the user had 2 accounts, each with its own user-provided names, the response could be:
 *
 * <pre class="prettyprint">
 *  new FillResponse.Builder()
 *      .add(new Dataset.Builder("Homer's Account")
 *          .setTextFieldValue(id1, "homer")
 *          .setTextFieldValue(id2, "D'OH!")
 *          .build())
 *      .add(new Dataset.Builder("Bart's Account")
 *          .setTextFieldValue(id1, "elbarto")
 *          .setTextFieldValue(id2, "cowabonga")
 *          .build())
 *      .build();
 * </pre>
 *
 * <p>If the user does not have any data associated with this {@link Activity} but the service wants
 * to offer the user the option to save the data that was entered, then the service could populate
 * the response with {@code savableIds} instead of {@link Dataset}s:
 *
 * <pre class="prettyprint">
 *  new FillResponse.Builder()
 *      .addSavableFields(id1, id2)
 *      .build();
 * </pre>
 *
 * <p>Similarly, there might be cases where the user data on the service is enough to populate some
 * fields but not all, and the service would still be interested on saving the other fields. In this
 * scenario, the service could populate the response with both {@link Dataset}s and {@code
 * savableIds}:
 *
 * <pre class="prettyprint">
 *   new FillResponse.Builder()
 *       .add(new Dataset.Builder("Homer")
 *          .setTextFieldValue(id1, "Homer")                  // first name
 *          .setTextFieldValue(id2, "Simpson")                // last name
 *          .setTextFieldValue(id3, "742 Evergreen Terrace")  // street
 *          .setTextFieldValue(id4, "Springfield")            // city
 *          .build())
 *       .addSavableFields(id5, id6) // state and zipcode
 *       .build();
 *
 * </pre>
 *
 * <p>Notice that the ids that are part of a dataset (ids 1 to 4, in this example) are automatically
 * added to the {@code savableIds} list.
 *
 * <p>If the service has multiple {@link Dataset}s with multiple options for some fields on each
 * dataset (for example, multiple accounts with both a home and work address), then it should
 * "partition" the {@link Activity} in sections and populate the response with just a subset of the
 * data that would fulfill the first section; then once the user fills the first section and taps a
 * field from the next section, the Android system would issue another request for that section, and
 * so on. For example, the first response could be:
 *
 * <pre class="prettyprint">
 *  new FillResponse.Builder()
 *      .add(new Dataset.Builder("Homer")
 *          .setTextFieldValue(id1, "Homer")
 *          .setTextFieldValue(id2, "Simpson")
 *          .build())
 *      .add(new Dataset.Builder("Bart")
 *          .setTextFieldValue(id1, "Bart")
 *          .setTextFieldValue(id2, "Simpson")
 *          .build())
 *      .build();
 * </pre>
 *
 * <p>Then after the user picks the {@code Homer} dataset and taps the {@code Street} field to
 * trigger another auto-fill request, the second response could be:
 *
 * <pre class="prettyprint">
 *  new FillResponse.Builder()
 *      .add(new Dataset.Builder("Home")
 *          .setTextFieldValue(id3, "742 Evergreen Terrace")
 *          .setTextFieldValue(id4, "Springfield")
 *          .build())
 *      .add(new Dataset.Builder("Work")
 *          .setTextFieldValue(id3, "Springfield Nuclear Power Plant")
 *          .setTextFieldValue(id4, "Springfield")
 *          .build())
 *      .build();
 * </pre>
 *
 * <p>The service could require user authentication, either at the {@link FillResponse} or {@link
 * Dataset} levels, prior to auto-filling the activity - see {@link
 * FillResponse.Builder#requiresFingerprintAuthentication(CryptoObject, Bundle, int)}, {@link
 * FillResponse.Builder#requiresCustomAuthentication(Bundle, int)}, {@link
 * Dataset.Builder#requiresFingerprintAuthentication(CryptoObject, Bundle, int)}, and {@link
 * Dataset.Builder#requiresCustomAuthentication(Bundle, int)} for details.
 *
 * <p>Finally, the service can use the {@link FillResponse.Builder#setExtras(Bundle)} and/or {@link
 * Dataset.Builder#setExtras(Bundle)} methods to pass {@link Bundle}s with service-specific data use
 * to identify this response on future calls (like {@link
 * android.service.autofill.AutoFillService#onSaveRequest(android.app.assist.AssistStructure,
 * Bundle, android.service.autofill.SaveCallback)}) - such bundles will be available as the
 * {@link android.service.autofill.AutoFillService#EXTRA_RESPONSE_EXTRAS} and
 * {@link android.service.autofill.AutoFillService#EXTRA_DATASET_EXTRAS} extras in that method's
 * {@code extras} argument.
 */
public final class FillResponse implements Parcelable {

    private final List<Dataset> mDatasets;
    private final AutoFillId[] mSavableIds;
    private final Bundle mExtras;
    private final int mFlags;
    private final boolean mRequiresAuth;
    private final boolean mHasCryptoObject;
    private final long mCryptoOpId;

    private FillResponse(Builder builder) {
        // TODO(b/33197203): make it immutable?
        mDatasets = builder.mDatasets;
        final int size = builder.mSavableIds.size();
        mSavableIds = new AutoFillId[size];
        int i = 0;
        for (AutoFillId id : builder.mSavableIds) {
            mSavableIds[i++] = id;
        }
        mExtras = builder.mExtras;
        mFlags = builder.mFlags;
        mRequiresAuth = builder.mRequiresAuth;
        mHasCryptoObject = builder.mHasCryptoObject;
        mCryptoOpId = builder.mCryptoOpId;
    }

    /** @hide */
    public List<Dataset> getDatasets() {
        return mDatasets;
    }

    /** @hide */
    public AutoFillId[] getSavableIds() {
        return mSavableIds;
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
    public boolean hasCryptoObject() {
        return mHasCryptoObject;
    }

    /** @hide */
    public long getCryptoObjectOpId() {
        return mCryptoOpId;
    }

    /**
     * Builder for {@link FillResponse} objects.
     */
    public static final class Builder {
        private final List<Dataset> mDatasets = new ArrayList<>();
        private final Set<AutoFillId> mSavableIds = new HashSet<>();
        private Bundle mExtras;
        private int mFlags;
        private boolean mRequiresAuth;
        private boolean mHasCryptoObject;
        private long mCryptoOpId;

        /**
         * Requires user authentication through the {@link android.service.autofill.AutoFillService}
         * before handling an auto-fill request.
         *
         * <p>This method is typically called when the device (or the service) does not support
         * fingerprint authentication (and hence it cannot use {@link
         * #requiresFingerprintAuthentication(CryptoObject, Bundle, int)}) or when the service needs
         * to use a custom authentication UI and is used in 2 scenarios:
         *
         * <ol>
         *   <li>When the user data is encrypted and the service must authenticate an object that
         *       will be used to decrypt it.
         *   <li>When the service already acquired the user data but wants to confirm the user's
         *       identity before the activity is filled with it.
         * </ol>
         *
         * <p>When this method is called, the Android System displays an UI affordance asking the
         * user to tap it to auto-fill the activity; if the user taps it, the Android System calls
         * {@link
         * android.service.autofill.AutoFillService#onFillResponseAuthenticationRequest(Bundle,
         * int)} passing {@link
         * android.service.autofill.AutoFillService#FLAG_AUTHENTICATION_REQUESTED} in the flags and
         * the same {@code extras} passed to this method. The service can then displays its custom
         * authentication UI, and then call the proper method on {@link FillCallback} depending on
         * the authentication result and whether this response already contains the {@link Dataset}s
         * need to auto-fill the activity:
         *
         * <ul>
         *   <li>If authentication failed, call {@link
         *       FillCallback#onFillResponseAuthentication(int)} passing {@link
         *       android.service.autofill.AutoFillService#FLAG_AUTHENTICATION_ERROR} in the flags.
         *   <li>If authentication succeeded and this response is empty (no datasets), call {@link
         *       FillCallback#onSuccess(FillResponse)} with a new dataset (that does not require
         *       authentication).
         *   <li>If authentication succeeded and this response is not empty, call {@link
         *       FillCallback#onFillResponseAuthentication(int)} passing {@link
         *       android.service.autofill.AutoFillService#FLAG_AUTHENTICATION_SUCCESS} in the flags.
         * </ul>
         *
         * @param extras when set, will be passed back in the {@link
         *     android.service.autofill.AutoFillService#onFillResponseAuthenticationRequest(Bundle,
         *     int)} call so it could be used by the service to handle state.
         * @param flags optional parameters, currently ignored.
         */
        public Builder requiresCustomAuthentication(@Nullable Bundle extras, int flags) {
            return requiresAuthentication(null, extras, flags);
        }

        /**
         * Requires user authentication through the Fingerprint sensor before handling an auto-fill
         * request.
         *
         * <p>The {@link android.service.autofill.AutoFillService} typically uses this method in 2
         * situations:
         *
         * <ol>
         *   <li>When the user data is encrypted and the service must authenticate an object that
         *       will be used to decrypt it.
         *   <li>When the service already acquired the user data but wants to confirm the user's
         *       identity before the activity is filled with it.
         * </ol>
         *
         * <p>When this method is called, the Android System displays an UI affordance asking the
         * user to use the fingerprint sensor to auto-fill the activity, and what happens after a
         * successful fingerprint authentication depends on the number of {@link Dataset}s included
         * in this response:
         *
         * <ul>
         *   <li>If it's empty (scenario #1 above), the Android System will call {@link
         *     android.service.autofill.AutoFillService#onFillResponseAuthenticationRequest(Bundle,
         *       int)} passing {@link
         *       android.service.autofill.AutoFillService#FLAG_AUTHENTICATION_SUCCESS}} in the
         *       flags.
         *   <li>If it contains one dataset, the activity will be auto-filled right away.
         *   <li>If it contains many datasets, the Android System will show dataset picker UI, and
         *       then auto-fill the activity once the user select the proper datased.
         * </ul>
         *
         * <p>If the fingerprint authentication fails, the Android System will call {@link
         * android.service.autofill.AutoFillService#onFillResponseAuthenticationRequest(Bundle,
         * int)} passing {@link android.service.autofill.AutoFillService#FLAG_AUTHENTICATION_ERROR}
         * in the flags.
         *
         * <p><strong>NOTE: </note> the {@link android.service.autofill.AutoFillService} should use
         * the {@link android.hardware.fingerprint.FingerprintManager} to check if fingerpint
         * authentication is available before using this method, and use other alternatives (such as
         * {@link #requiresCustomAuthentication(Bundle, int)}) if it is not: if this method is
         * called when fingerprint is not available, Android System will call {@link
         * android.service.autofill.AutoFillService#onFillResponseAuthenticationRequest(Bundle,
         * int)} passing {@link
         * android.service.autofill.AutoFillService#FLAG_FINGERPRINT_AUTHENTICATION_NOT_AVAILABLE}
         * in the flags, but it would be wasting system resources (and worsening the user
         * experience) in the process.
         *
         * @param crypto object that will be authenticated.
         * @param extras when set, will be passed back in the {@link
         *     android.service.autofill.AutoFillService#onFillResponseAuthenticationRequest(Bundle,
         *     int)} call so it could be used by the service to handle state.
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
         * Adds a new {@link Dataset} to this response.
         *
         * @throws IllegalArgumentException if a dataset with same {@code name} already exists.
         */
        public Builder addDataset(Dataset dataset) {
            Preconditions.checkNotNull(dataset, "dataset cannot be null");
            // TODO(b/33197203): check if name already exists
            mDatasets.add(dataset);
            for (DatasetField field : dataset.getFields()) {
                mSavableIds.add(field.getId());
            }
            return this;
        }

        /**
         * Adds ids of additional fields that the service would be interested to save (through
         * {@link android.service.autofill.AutoFillService#onSaveRequest(
         * android.app.assist.AssistStructure, Bundle, android.service.autofill.SaveCallback)})
         * but were not indirectly set through {@link #addDataset(Dataset)}.
         *
         * <p>See {@link FillResponse} for examples.
         */
        public Builder addSavableFields(AutoFillId... ids) {
            for (AutoFillId id : ids) {
                mSavableIds.add(id);
            }
            return this;
        }

        /**
         * Sets a {@link Bundle} that will be passed to subsequent calls to {@link
         * android.service.autofill.AutoFillService} methods such as {@link
         * android.service.autofill.AutoFillService#onSaveRequest(
         * android.app.assist.AssistStructure, Bundle, android.service.autofill.SaveCallback)},
         * using {@link
         * android.service.autofill.AutoFillService#EXTRA_RESPONSE_EXTRAS} as the key.
         *
         * <p>It can be used when to keep service state in between calls.
         */
        public Builder setExtras(Bundle extras) {
            // TODO(b/33197203): make sure that either this method or the requires-Authentication
            // ones are called, but not both
            mExtras = Objects.requireNonNull(extras, "extras cannot be null");
            return this;
        }

        /**
         * Builds a new {@link FillResponse} instance.
         */
        public FillResponse build() {
            return new FillResponse(this);
        }
    }

    /////////////////////////////////////
    //  Object "contract" methods. //
    /////////////////////////////////////
    @Override
    public String toString() {
        if (!DEBUG) return super.toString();

        final StringBuilder builder = new StringBuilder("FillResponse: [datasets=")
                .append(mDatasets).append(", savableIds=").append(Arrays.toString(mSavableIds))
                .append(", extras=");
        append(builder, mExtras)
            .append(", flags=").append(mFlags)
            .append(", requiresAuth: ").append(mRequiresAuth)
            .append(", hasCrypto: ").append(mHasCryptoObject);
        return builder.append(']').toString();
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
        parcel.writeList(mDatasets);
        parcel.writeParcelableArray(mSavableIds, 0);
        parcel.writeBundle(mExtras);
        parcel.writeInt(mFlags);
        parcel.writeInt(mRequiresAuth ? 1 : 0);
        parcel.writeInt(mHasCryptoObject ? 1 : 0);
        if (mHasCryptoObject) {
            parcel.writeLong(mCryptoOpId);
        }
    }

    private FillResponse(Parcel parcel) {
        mDatasets = new ArrayList<>();
        parcel.readList(mDatasets, null);
        mSavableIds = parcel.readParcelableArray(null, AutoFillId.class);
        mExtras = parcel.readBundle();
        mFlags = parcel.readInt();
        mRequiresAuth = parcel.readInt() == 1;
        mHasCryptoObject = parcel.readInt() == 1;
        mCryptoOpId = mHasCryptoObject ? parcel.readLong() : 0;
    }

    public static final Parcelable.Creator<FillResponse> CREATOR =
            new Parcelable.Creator<FillResponse>() {
        @Override
        public FillResponse createFromParcel(Parcel source) {
            return new FillResponse(source);
        }

        @Override
        public FillResponse[] newArray(int size) {
            return new FillResponse[size];
        }
    };
}
