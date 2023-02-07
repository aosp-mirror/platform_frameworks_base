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
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.slice.Slice;
import android.credentials.GetCredentialResponse;
import android.os.Parcel;
import android.os.Parcelable;

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
 *
 * <p>Any class that derives this class must only add extra field values to the {@code slice}
 * object passed into the constructor. Any other field will not be parceled through. If the
 * derived class has custom parceling implementation, this class will not be able to unpack
 * the parcel without having access to that implementation.
 *
 * <p>While creating this entry, providers must set a {@code requestId} to be retrieved
 * from {@link BeginGetCredentialOption#getId()}, to determine for which request this entry is
 * being presented to the user. This will ensure that when user selects the entry, the correct
 * complete request is added to the {@link PendingIntent} mentioned above.
 */
@SuppressLint("ParcelNotFinal")
public class CredentialEntry implements Parcelable {
    /** The request option that corresponds to this entry. **/
    private final @Nullable BeginGetCredentialOption mBeginGetCredentialOption;

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
     * @param beginGetCredentialOption the request option for which this credential entry is
     *                                 being constructed This helps maintain an association,
     *                                 such that when the user selects this entry, providers
     *                                 can receive the conmplete corresponding request.
     * @param slice the slice containing the metadata to be shown on the UI. Must be
     *              constructed through the androidx.credentials jetpack library.
     */
    public CredentialEntry(@NonNull BeginGetCredentialOption beginGetCredentialOption,
            @NonNull Slice slice) {
        mBeginGetCredentialOption = requireNonNull(beginGetCredentialOption,
                "beginGetCredentialOption must not be null");
        mType = requireNonNull(mBeginGetCredentialOption.getType(),
                "type must not be null");
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
     * @hide
     */
    // TODO: Unhide this constructor when the registry APIs are stable
    public CredentialEntry(@NonNull String type, @NonNull Slice slice) {
        mBeginGetCredentialOption = null;
        mType = requireNonNull(type, "type must not be null");
        mSlice = requireNonNull(slice, "slice must not be null");
    }

    private CredentialEntry(@NonNull Parcel in) {
        mType = in.readString8();
        mSlice = in.readTypedObject(Slice.CREATOR);
        mBeginGetCredentialOption = in.readTypedObject(BeginGetCredentialOption.CREATOR);
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
        dest.writeTypedObject(mBeginGetCredentialOption, flags);
    }

    /**
     * Returns the request option for which this credential entry has been constructed.
     */
    @NonNull
    public BeginGetCredentialOption getBeginGetCredentialOption() {
        return mBeginGetCredentialOption;
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
