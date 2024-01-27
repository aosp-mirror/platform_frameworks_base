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

package android.credentials;

import android.annotation.Hide;
import android.annotation.NonNull;
import android.app.PendingIntent;
import android.credentials.selection.GetCredentialProviderData;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;

/**
 * A list of candidate credentials.
 *
 * @hide
 */
@Hide
public final class GetCandidateCredentialsResponse implements Parcelable {
    @NonNull
    private final List<GetCredentialProviderData> mCandidateProviderDataList;

    private final PendingIntent mPendingIntent;

    private final GetCredentialResponse mGetCredentialResponse;

    /**
     * @hide
     */
    @Hide
    public GetCandidateCredentialsResponse(
            GetCredentialResponse getCredentialResponse
    ) {
        mCandidateProviderDataList = null;
        mPendingIntent = null;
        mGetCredentialResponse = getCredentialResponse;
    }

    /**
     * @hide
     */
    @Hide
    public GetCandidateCredentialsResponse(
            List<GetCredentialProviderData> candidateProviderDataList,
            PendingIntent pendingIntent
    ) {
        Preconditions.checkCollectionNotEmpty(
                candidateProviderDataList,
                /*valueName=*/ "candidateProviderDataList");
        mCandidateProviderDataList = new ArrayList<>(candidateProviderDataList);
        mPendingIntent = pendingIntent;
        mGetCredentialResponse = null;
    }

    /**
     * Returns candidate provider data list.
     *
     * @hide
     */
    public List<GetCredentialProviderData> getCandidateProviderDataList() {
        return mCandidateProviderDataList;
    }

    /**
     * Returns candidate provider data list.
     *
     * @hide
     */
    public GetCredentialResponse getGetCredentialResponse() {
        return mGetCredentialResponse;
    }

    /**
     * Returns candidate provider data list.
     *
     * @hide
     */
    public PendingIntent getPendingIntent() {
        return mPendingIntent;
    }

    protected GetCandidateCredentialsResponse(Parcel in) {
        List<GetCredentialProviderData> candidateProviderDataList = new ArrayList<>();
        in.readTypedList(candidateProviderDataList, GetCredentialProviderData.CREATOR);
        mCandidateProviderDataList = candidateProviderDataList;

        AnnotationValidations.validate(NonNull.class, null, mCandidateProviderDataList);

        mPendingIntent = in.readTypedObject(PendingIntent.CREATOR);
        mGetCredentialResponse = in.readTypedObject(GetCredentialResponse.CREATOR);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedList(mCandidateProviderDataList);
        dest.writeTypedObject(mPendingIntent, flags);
        dest.writeTypedObject(mGetCredentialResponse, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<GetCandidateCredentialsResponse> CREATOR =
            new Creator<>() {
                @Override
                public GetCandidateCredentialsResponse createFromParcel(Parcel in) {
                    return new GetCandidateCredentialsResponse(in);
                }

                @Override
                public GetCandidateCredentialsResponse[] newArray(int size) {
                    return new GetCandidateCredentialsResponse[size];
                }
            };
}
