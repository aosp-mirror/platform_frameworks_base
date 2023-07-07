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

import android.annotation.NonNull;
import android.credentials.CredentialOption;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Request for getting user's credential from a given credential provider.
 *
 * <p>A credential provider will receive this request once the user selects a
 * given {@link CredentialEntry}, or {@link RemoteEntry} on the selector, that was sourced
 * from provider's initial response to {@link CredentialProviderService#onBeginGetCredential}.
 */
public final class GetCredentialRequest implements Parcelable {
    /** Calling package of the app requesting for credentials. */
    @NonNull
    private final CallingAppInfo mCallingAppInfo;

    /**
     * Holds a list of options (parameters) to be used for retrieving a specific type of credential.
     */
    @NonNull
    private final List<CredentialOption> mCredentialOptions;

    public GetCredentialRequest(@NonNull CallingAppInfo callingAppInfo,
            @NonNull List<CredentialOption> credentialOptions) {
        this.mCallingAppInfo = Objects.requireNonNull(callingAppInfo,
                "callingAppInfo must not be null");
        this.mCredentialOptions = Objects.requireNonNull(credentialOptions,
                "credentialOptions must not be null");
    }

    private GetCredentialRequest(@NonNull Parcel in) {
        mCallingAppInfo = in.readTypedObject(CallingAppInfo.CREATOR);
        AnnotationValidations.validate(NonNull.class, null, mCallingAppInfo);

        List<CredentialOption> credentialOptions = new ArrayList<>();
        in.readTypedList(credentialOptions, CredentialOption.CREATOR);
        mCredentialOptions = credentialOptions;
        AnnotationValidations.validate(NonNull.class, null, mCredentialOptions);
    }

    @NonNull public static final  Creator<GetCredentialRequest> CREATOR =
            new Creator<>() {
                @Override
                public GetCredentialRequest createFromParcel(Parcel in) {
                    return new GetCredentialRequest(in);
                }

                @Override
                public GetCredentialRequest[] newArray(int size) {
                    return new GetCredentialRequest[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mCallingAppInfo, flags);
        dest.writeTypedList(mCredentialOptions, flags);
    }

    /**
     * Returns info pertaining to the app requesting credentials.
     */
    @NonNull
    public CallingAppInfo getCallingAppInfo() {
        return mCallingAppInfo;
    }

    /**
     * Returns a list of options containing parameters needed to return a given type of credential.
     * This is part of the request that the credential provider receives after the user has
     * selected an entry on a selector UI.
     *
     * When the user selects a {@link CredentialEntry} and the credential provider receives a
     * {@link GetCredentialRequest}, this list is expected to contain a single
     * {@link CredentialOption} only. A {@link CredentialEntry} is always created for a given
     * {@link BeginGetCredentialOption}, and hence when the user selects it, the provider
     * receives a corresponding {@link CredentialOption} that contains all the required parameters
     * to actually retrieve the credential.
     *
     * When the user selects a {@link RemoteEntry} and the credential provider receives a
     * {@link GetCredentialRequest}, this list may contain greater than a single
     * {@link CredentialOption}, representing the number of options specified by the developer
     * in the original {@link android.credentials.GetCredentialRequest}. This is because a
     * {@link RemoteEntry} indicates that the entire request will be processed on a different
     * device and is not tied to a particular option.
     */
    @NonNull
    public List<CredentialOption> getCredentialOptions() {
        return mCredentialOptions;
    }
}
