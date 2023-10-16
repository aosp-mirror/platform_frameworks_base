/*
 * Copyright 2022 The Android Open Source Project
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
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;
import com.android.internal.util.Preconditions;

/**
 * A specific type of credential request to be sent to the provider during the query phase of
 * a get flow. This request contains limited parameters needed to populate a list of
 * {@link CredentialEntry} on the {@link BeginGetCredentialResponse}.
 */
public final class BeginGetCredentialOption implements Parcelable {
    private static final String BUNDLE_ID_KEY =
            "android.service.credentials.BeginGetCredentialOption.BUNDLE_ID_KEY";
    /**
     * A unique id associated with this request option.
     */
    @NonNull
    private final String mId;

    /**
     * The requested credential type.
     */
    @NonNull
    private final String mType;

    /**
     * The request candidateQueryData.
     */
    @NonNull
    private final Bundle mCandidateQueryData;

    /**
     * Returns the unique id associated with this request. This is for internal use only.
     */
    @NonNull
    public String getId() {
        return mId;
    }

    /**
     * Returns the requested credential type.
     */
    @NonNull
    public String getType() {
        return mType;
    }

    /**
     * Returns the request candidate query data, denoting a set of parameters
     * that can be used to populate a candidate list of credentials, as
     * {@link CredentialEntry} on {@link BeginGetCredentialResponse}. This list
     * of entries is then presented to the user on a selector.
     *
     * <p>This data does not contain any sensitive parameters, and will be sent
     * to all eligible providers.
     * The complete set of parameters will only be set on the {@link android.app.PendingIntent}
     * set on the {@link CredentialEntry} that is selected by the user.
     */
    @NonNull
    public Bundle getCandidateQueryData() {
        return mCandidateQueryData;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mType);
        dest.writeBundle(mCandidateQueryData);
        dest.writeString8(mId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "GetCredentialOption {"
                + "type=" + mType
                + ", candidateQueryData=" + mCandidateQueryData
                + ", id=" + mId
                + "}";
    }

    /**
     * Constructs a {@link BeginGetCredentialOption}.
     *
     * @param id the unique id associated with this option
     * @param type               the requested credential type
     * @param candidateQueryData the request candidateQueryData
     * @throws IllegalArgumentException If id or type is empty.
     */
    public BeginGetCredentialOption(
            @NonNull String id, @NonNull String type,
            @NonNull Bundle candidateQueryData) {
        mId = Preconditions.checkStringNotEmpty(id, "id must not be empty");
        mType = Preconditions.checkStringNotEmpty(type, "type must not be empty");
        Bundle bundle = new Bundle();
        bundle.putAll(candidateQueryData);
        mCandidateQueryData = bundle;
        addIdToBundle();
    }

    private void addIdToBundle() {
        mCandidateQueryData.putString(BUNDLE_ID_KEY, mId);
    }

    private BeginGetCredentialOption(@NonNull Parcel in) {
        String type = in.readString8();
        Bundle candidateQueryData = in.readBundle();
        String id = in.readString8();

        mType = type;
        AnnotationValidations.validate(NonNull.class, null, mType);
        mCandidateQueryData = candidateQueryData;
        AnnotationValidations.validate(NonNull.class, null, mCandidateQueryData);
        mId = id;
    }

    public static final @NonNull Creator<BeginGetCredentialOption> CREATOR =
            new Creator<BeginGetCredentialOption>() {
                @Override
                public BeginGetCredentialOption[] newArray(int size) {
                    return new BeginGetCredentialOption[size];
                }

                @Override
                public BeginGetCredentialOption createFromParcel(@NonNull Parcel in) {
                    return new BeginGetCredentialOption(in);
                }
            };
}
