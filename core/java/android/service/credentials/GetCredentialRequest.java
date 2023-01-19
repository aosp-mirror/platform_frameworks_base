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

/**
 * Request for getting user's credential from a given credential provider.
 *
 * <p>Provider will receive this request once the user selects a given {@link CredentialEntry}
 * on the selector, that was sourced from provider's result to
 * {@link CredentialProviderService#onBeginGetCredential}.
 */
public final class GetCredentialRequest implements Parcelable {
    /** Calling package of the app requesting for credentials. */
    @NonNull
    private final CallingAppInfo mCallingAppInfo;

    /**
     * Holds parameters to be used for retrieving a specific type of credential.
     */
    @NonNull
    private final CredentialOption mCredentialOption;

    public GetCredentialRequest(@NonNull CallingAppInfo callingAppInfo,
            @NonNull CredentialOption credentialOption) {
        this.mCallingAppInfo = callingAppInfo;
        this.mCredentialOption = credentialOption;
    }

    private GetCredentialRequest(@NonNull Parcel in) {
        mCallingAppInfo = in.readTypedObject(CallingAppInfo.CREATOR);
        AnnotationValidations.validate(NonNull.class, null, mCallingAppInfo);
        mCredentialOption = in.readTypedObject(CredentialOption.CREATOR);
        AnnotationValidations.validate(NonNull.class, null, mCredentialOption);
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
        dest.writeTypedObject(mCredentialOption, flags);
    }

    /**
     * Returns info pertaining to the app requesting credentials.
     */
    @NonNull
    public CallingAppInfo getCallingAppInfo() {
        return mCallingAppInfo;
    }

    /**
     * Returns the parameters needed to return a given type of credential.
     */
    @NonNull
    public CredentialOption getGetCredentialOption() {
        return mCredentialOption;
    }
}
