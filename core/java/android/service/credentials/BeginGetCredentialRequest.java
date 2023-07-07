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
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Query stage request for getting user's credentials from a given credential provider.
 *
 * <p>This request contains a list of {@link BeginGetCredentialOption} that have parameters
 * to be used to query credentials, and return a list of {@link CredentialEntry} to be set
 * on the {@link BeginGetCredentialResponse}. This list is then shown to the user on a selector.
 *
 * If a {@link PendingIntent} is set on a {@link CredentialEntry}, and the user selects that
 * entry, a {@link GetCredentialRequest} with all parameters needed to get the actual
 * {@link android.credentials.Credential} will be sent as part of the {@link Intent} fired
 * through the {@link PendingIntent}.
 */
public final class BeginGetCredentialRequest implements Parcelable {
    /** Info pertaining to the app requesting for credentials. */
    @Nullable private final CallingAppInfo mCallingAppInfo;

    /**
     * List of credential options. Each {@link BeginGetCredentialOption} object holds parameters to
     * be used for populating a list of {@link CredentialEntry} for a specific type of credential.
     *
     * This request does not reveal sensitive parameters. Complete list of parameters
     * is retrieved through the {@link PendingIntent} set on each {@link CredentialEntry}
     * on {@link BeginGetCredentialResponse} set on {@link BeginGetCredentialResponse},
     * when the user selects one of these entries.
     */
    @NonNull private final List<BeginGetCredentialOption> mBeginGetCredentialOptions;

    private BeginGetCredentialRequest(@Nullable CallingAppInfo callingAppInfo,
            @NonNull List<BeginGetCredentialOption> getBeginCredentialOptions) {
        this.mCallingAppInfo = callingAppInfo;
        this.mBeginGetCredentialOptions = getBeginCredentialOptions;
    }

    private BeginGetCredentialRequest(@NonNull Parcel in) {
        mCallingAppInfo = in.readTypedObject(CallingAppInfo.CREATOR);
        List<BeginGetCredentialOption> getBeginCredentialOptions = new ArrayList<>();
        in.readTypedList(getBeginCredentialOptions, BeginGetCredentialOption.CREATOR);
        mBeginGetCredentialOptions = getBeginCredentialOptions;
        AnnotationValidations.validate(NonNull.class, null, mBeginGetCredentialOptions);
    }

    @NonNull
    public static final Creator<BeginGetCredentialRequest> CREATOR =
            new Creator<BeginGetCredentialRequest>() {
                @Override
                public BeginGetCredentialRequest createFromParcel(Parcel in) {
                    return new BeginGetCredentialRequest(in);
                }

                @Override
                public BeginGetCredentialRequest[] newArray(int size) {
                    return new BeginGetCredentialRequest[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mCallingAppInfo, flags);
        dest.writeTypedList(mBeginGetCredentialOptions);
    }

    /**
     * Returns info pertaining to the app requesting credentials.
     */
    public @Nullable CallingAppInfo getCallingAppInfo() {
        return mCallingAppInfo;
    }

    /**
     * Returns the list of type specific credential options to list credentials for in
     * {@link BeginGetCredentialResponse}.
     */
    public @NonNull List<BeginGetCredentialOption> getBeginGetCredentialOptions() {
        return mBeginGetCredentialOptions;
    }

    /**
     * Builder for {@link BeginGetCredentialRequest}.
     */
    public static final class Builder {
        private CallingAppInfo mCallingAppInfo = null;
        private List<BeginGetCredentialOption> mBeginGetCredentialOptions = new ArrayList<>();

        /**
         * Sets information pertaining to the calling app.
         * @param callingAppInfo the info object containing the package name, and app signatures
         */
        public @NonNull Builder setCallingAppInfo(@Nullable CallingAppInfo callingAppInfo) {
            mCallingAppInfo = callingAppInfo;
            return this;
        }

        /**
         * Sets the list of credential options.
         *
         * @throws NullPointerException If {@code getBeginCredentialOptions} itself or any of its
         * elements is null.
         * @throws IllegalArgumentException If {@code getBeginCredentialOptions} is empty.
         */
        public @NonNull Builder setBeginGetCredentialOptions(
                @NonNull List<BeginGetCredentialOption> getBeginCredentialOptions) {
            Preconditions.checkCollectionNotEmpty(getBeginCredentialOptions,
                    "getBeginCredentialOptions");
            Preconditions.checkCollectionElementsNotNull(getBeginCredentialOptions,
                    "getBeginCredentialOptions");
            mBeginGetCredentialOptions = getBeginCredentialOptions;
            return this;
        }

        /**
         * Adds a single {@link BeginGetCredentialOption} object to the list of credential options.
         *
         * @throws NullPointerException If {@code beginGetCredentialOption} is null.
         */
        public @NonNull Builder addBeginGetCredentialOption(
                @NonNull BeginGetCredentialOption beginGetCredentialOption) {
            Objects.requireNonNull(beginGetCredentialOption,
                    "beginGetCredentialOption must not be null");
            mBeginGetCredentialOptions.add(beginGetCredentialOption);
            return this;
        }

        /**
         * Builds a new {@link BeginGetCredentialRequest} instance.
         *
         * @throws NullPointerException If {@code beginGetCredentialOptions} is null.
         * @throws IllegalArgumentException If {@code beginGetCredentialOptions} is empty, or if
         * {@code callingAppInfo} is null or empty.
         */
        public @NonNull BeginGetCredentialRequest build() {
            Preconditions.checkCollectionNotEmpty(mBeginGetCredentialOptions,
                    "beginGetCredentialOptions");
            return new BeginGetCredentialRequest(mCallingAppInfo, mBeginGetCredentialOptions);
        }
    }
}
