/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.service.credentials;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.PendingIntent;
import android.app.slice.Slice;
import android.credentials.GetCredentialResponse;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

/**
 * A credential entry that is to be displayed on the account selector that is presented to the
 * user.
 *
 * <p>If user selects this entry, the corresponding {@link PendingIntent},
 * set on the {@code slice} will be invoked to launch activities that require some user engagement
 * before getting the credential corresponding to this entry, e.g. authentication,
 * confirmation etc. The extras associated with the resulting {@link android.app.Activity} will
 * also contain the complete credential request containing all required parameters. This request
 * can be retrieved against {@link CredentialProviderService#EXTRA_GET_CREDENTIAL_REQUEST}.
 *
 * Once the activity fulfills the required user engagement, the {@link android.app.Activity}
 * result should be set to {@link android.app.Activity#RESULT_OK}, and the
 * {@link CredentialProviderService#EXTRA_GET_CREDENTIAL_RESPONSE} must be set with a
 * {@link GetCredentialResponse} object.
 */
public final class CredentialEntry implements Parcelable {
    /** The request option that corresponds to this entry. **/
    private final @Nullable String mBeginGetCredentialOptionId;

    /** The type of the credential entry to be shown on the UI. */
    private final @NonNull String mType;


    /** The object containing display content to be shown along with this credential entry
     * on the UI. */
    private final @NonNull Slice mSlice;

    /**
     * Creates an entry that is associated with a {@link BeginGetCredentialOption} request.
     * Providers must use this constructor when they extend from {@link CredentialProviderService}
     * to respond to query phase {@link CredentialProviderService#onBeginGetCredential}
     * credential retrieval requests.
     *
     * @param beginGetCredentialOptionId the beginGetCredentialOptionId to be retrieved from
     * {@link BeginGetCredentialOption#getId()} - the request option for which this CredentialEntry
     *                                   is being constructed This helps maintain an association
     *                                   such that when the user selects this entry, providers can
     *                                   receive the complete corresponding
     *                                   {@link GetCredentialRequest}.
     * @param type the type of the credential for which this credential entry is being created
     * @param slice the slice containing the metadata to be shown on the UI, must be constructed
     *              through the {@link androidx.credentials.provider} Jetpack library;
     *              If constructed manually, the {@code slice} object must
     *              contain the non-null properties of the
     *              {@link androidx.credentials.provider.CredentialEntry} class populated as slice
     *              items against specific hints as used in the class's {@code toSlice} method,
     *              since the Android System uses this library to parse the {@code slice} and
     *              extract the required attributes
     *
     * @throws IllegalArgumentException If {@code beginGetCredentialOptionId} or {@code type}
     * is null, or empty
     */
    public CredentialEntry(@NonNull String beginGetCredentialOptionId, @NonNull String type,
            @NonNull Slice slice) {
        mBeginGetCredentialOptionId = Preconditions.checkStringNotEmpty(
                beginGetCredentialOptionId, "beginGetCredentialOptionId must not be "
                        + "null, or empty");
        mType = Preconditions.checkStringNotEmpty(type, "type must not be null, or "
                + "empty");
        mSlice = requireNonNull(slice, "slice must not be null");
    }

    /**
     * Creates an entry that is associated with a {@link BeginGetCredentialOption} request.
     * Providers must use this constructor when they extend from {@link CredentialProviderService}
     * to respond to query phase {@link CredentialProviderService#onBeginGetCredential}
     * credential retrieval requests.
     *
     * @param beginGetCredentialOption the request option for which this credential entry is
     *                                 being constructed This helps maintain an association,
     *                                 such that when the user selects this entry, providers
     *                                 can receive the complete corresponding request.
     * @param slice the slice containing the metadata to be shown on the UI. Must be
     *              constructed through the androidx.credentials jetpack library.
     */
    public CredentialEntry(@NonNull BeginGetCredentialOption beginGetCredentialOption,
            @NonNull Slice slice) {
        requireNonNull(beginGetCredentialOption, "beginGetCredentialOption must not"
                + " be null");
        mBeginGetCredentialOptionId = Preconditions.checkStringNotEmpty(
                beginGetCredentialOption.getId(), "Id in beginGetCredentialOption "
                        + "must not be null");
        mType = Preconditions.checkStringNotEmpty(beginGetCredentialOption.getType(),
                "type in beginGetCredentialOption must not be null");
        mSlice = requireNonNull(slice, "slice must not be null");
    }

    /**
     * Creates an entry that is independent of an incoming {@link BeginGetCredentialOption}
     * request. Providers must use this constructor for constructing entries to be registered
     * with the framework outside of the span of an API call.
     *
     * @param type the type of the credential
     * @param slice the slice containing the metadata to be shown on the UI. Must be
     *              constructed through the androidx.credentials jetpack library.
     *
     */
    public CredentialEntry(@NonNull String type, @NonNull Slice slice) {
        mBeginGetCredentialOptionId = null;
        mType = requireNonNull(type, "type must not be null");
        mSlice = requireNonNull(slice, "slice must not be null");
    }

    private CredentialEntry(@NonNull Parcel in) {
        requireNonNull(in, "parcel must not be null");
        mType = in.readString8();
        mSlice = in.readTypedObject(Slice.CREATOR);
        mBeginGetCredentialOptionId = in.readString8();
    }

    @NonNull
    public static final Creator<CredentialEntry> CREATOR =
            new Creator<CredentialEntry>() {
                @Override
                public CredentialEntry createFromParcel(@NonNull Parcel in) {
                    return new CredentialEntry(in);
                }

                @Override
                public CredentialEntry[] newArray(int size) {
                    return new CredentialEntry[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mType);
        dest.writeTypedObject(mSlice, flags);
        dest.writeString8(mBeginGetCredentialOptionId);
    }

    /**
     * Returns the id of the {@link BeginGetCredentialOption} for which this credential
     * entry has been constructed.
     */
    @NonNull
    public String getBeginGetCredentialOptionId() {
        return mBeginGetCredentialOptionId;
    }

    /**
     * Returns the specific credential type of the entry.
     */
    @NonNull
    public String getType() {
        return mType;
    }

    /**
     * Returns the {@link Slice} object containing UI display content to be shown for this entry.
     */
    @NonNull
    public Slice getSlice() {
        return mSlice;
    }
}
