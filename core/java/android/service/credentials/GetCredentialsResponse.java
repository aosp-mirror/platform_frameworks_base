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

import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * Response from a credential provider, containing credential entries and other associated
 * data to be shown on the account selector UI.
 *
 * @hide
 */
public final class GetCredentialsResponse implements Parcelable {
    /** Content to be used for the UI. */
    private final @Nullable CredentialsDisplayContent mCredentialsDisplayContent;

    /**
     * Authentication action that must be launched and completed before showing any content
     * from the provider.
     */
    private final @Nullable Action mAuthenticationAction;

    /**
     * Creates a {@link GetCredentialsRequest} instance with an authentication action set.
     * Providers must use this method when no content can be shown before authentication.
     *
     * @throws NullPointerException If {@code authenticationAction} is null.
     */
    public static @NonNull GetCredentialsResponse createWithAuthentication(
            @NonNull Action authenticationAction) {
        Objects.requireNonNull(authenticationAction,
                "authenticationAction must not be null");
        return new GetCredentialsResponse(null, authenticationAction);
    }

    /**
     * Creates a {@link GetCredentialsRequest} instance with display content to be shown on the UI.
     * Providers must use this method when there is content to be shown without top level
     * authentication required.
     *
     * @throws NullPointerException If {@code credentialsDisplayContent} is null.
     */
    public static @NonNull GetCredentialsResponse createWithDisplayContent(
            @NonNull CredentialsDisplayContent credentialsDisplayContent) {
        Objects.requireNonNull(credentialsDisplayContent,
                "credentialsDisplayContent must not be null");
        return new GetCredentialsResponse(credentialsDisplayContent, null);
    }

    private GetCredentialsResponse(@Nullable CredentialsDisplayContent credentialsDisplayContent,
            @Nullable Action authenticationAction) {
        mCredentialsDisplayContent = credentialsDisplayContent;
        mAuthenticationAction = authenticationAction;
    }

    private GetCredentialsResponse(@NonNull Parcel in) {
        mCredentialsDisplayContent = in.readParcelable(CredentialsDisplayContent.class
                .getClassLoader(), CredentialsDisplayContent.class);
        mAuthenticationAction = in.readParcelable(Action.class.getClassLoader(), Action.class);
    }

    public static final @NonNull Creator<GetCredentialsResponse> CREATOR =
            new Creator<GetCredentialsResponse>() {
                @Override
                public GetCredentialsResponse createFromParcel(Parcel in) {
                    return new GetCredentialsResponse(in);
                }

                @Override
                public GetCredentialsResponse[] newArray(int size) {
                    return new GetCredentialsResponse[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mCredentialsDisplayContent, flags);
        dest.writeParcelable(mAuthenticationAction, flags);
    }

    /**
     * Returns whether the response contains a top level authentication action.
     */
    public @NonNull boolean isAuthenticationActionSet() {
        return mAuthenticationAction != null;
    }

    /**
     * Returns the authentication action to be invoked before any other content
     * can be shown to the user.
     */
    public @NonNull Action getAuthenticationAction() {
        return mAuthenticationAction;
    }

    /**
     * Returns the credentialDisplayContent that does not require authentication, and
     * can be shown to the user on the account selector UI.
     */
    public @NonNull CredentialsDisplayContent getCredentialsDisplayContent() {
        return mCredentialsDisplayContent;
    }
}
