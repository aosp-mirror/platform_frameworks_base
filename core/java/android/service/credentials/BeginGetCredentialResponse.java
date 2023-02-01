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
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * Response from a credential provider, containing credential entries and other associated
 * data to be shown on the account selector UI.
 */
public final class BeginGetCredentialResponse implements Parcelable {
    /** Content to be used for the UI. */
    private final @Nullable CredentialsResponseContent mCredentialsResponseContent;

    /**
     * Authentication action that must be launched and completed before showing any content
     * from the provider.
     */
    private final @Nullable Action mAuthenticationAction;

    /**
     * Creates a {@link BeginGetCredentialResponse} instance with an authentication
     * {@link Action} set. Providers must use this method when no content can be shown
     * before authentication.
     *
     * <p> When the user selects this {@code authenticationAction}, the system invokes the
     * corresponding {@code pendingIntent}. Once the authentication flow is complete,
     * the {@link android.app.Activity} result should be set
     * to {@link android.app.Activity#RESULT_OK} and the
     * {@link CredentialProviderService#EXTRA_CREDENTIALS_RESPONSE_CONTENT} extra should be set
     * with a fully populated {@link CredentialsResponseContent} object.
     * the authentication action activity is launched, and the user is authenticated, providers
     * should create another response with {@link CredentialsResponseContent} using
     * {@code createWithDisplayContent}, and add that response to the result of the authentication
     * activity.
     *
     * @throws NullPointerException If {@code authenticationAction} is null.
     */
    public static @NonNull BeginGetCredentialResponse createWithAuthentication(
            @NonNull Action authenticationAction) {
        Objects.requireNonNull(authenticationAction,
                "authenticationAction must not be null");
        return new BeginGetCredentialResponse(null, authenticationAction);
    }

    /**
     * Creates a {@link BeginGetCredentialRequest} instance with content to be shown on the UI.
     * Providers must use this method when there is content to be shown without top level
     * authentication required, including credential entries, action entries or a remote entry,
     *
     * @throws NullPointerException If {@code credentialsResponseContent} is null.
     */
    public static @NonNull BeginGetCredentialResponse createWithResponseContent(
            @NonNull CredentialsResponseContent credentialsResponseContent) {
        Objects.requireNonNull(credentialsResponseContent,
                "credentialsResponseContent must not be null");
        return new BeginGetCredentialResponse(credentialsResponseContent, null);
    }

    private BeginGetCredentialResponse(@Nullable CredentialsResponseContent
            credentialsResponseContent,
            @Nullable Action authenticationAction) {
        mCredentialsResponseContent = credentialsResponseContent;
        mAuthenticationAction = authenticationAction;
    }

    private BeginGetCredentialResponse(@NonNull Parcel in) {
        mCredentialsResponseContent = in.readTypedObject(CredentialsResponseContent.CREATOR);
        mAuthenticationAction = in.readTypedObject(Action.CREATOR);
    }

    public static final @NonNull Creator<BeginGetCredentialResponse> CREATOR =
            new Creator<BeginGetCredentialResponse>() {
                @Override
                public BeginGetCredentialResponse createFromParcel(Parcel in) {
                    return new BeginGetCredentialResponse(in);
                }

                @Override
                public BeginGetCredentialResponse[] newArray(int size) {
                    return new BeginGetCredentialResponse[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mCredentialsResponseContent, flags);
        dest.writeTypedObject(mAuthenticationAction, flags);
    }

    /**
     * If this response represents a top level authentication action, returns the authentication
     * action to be invoked before any other content can be shown to the user.
     */
    public @Nullable Action getAuthenticationAction() {
        return mAuthenticationAction;
    }

    /**
     * Returns the actual content to be displayed on the selector, if this response does not
     * require any top level authentication.
     */
    public @Nullable CredentialsResponseContent getCredentialsResponseContent() {
        return mCredentialsResponseContent;
    }
}
