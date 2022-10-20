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

package android.credentials.ui;

import android.annotation.NonNull;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;

/**
 * User selection result information of a UX flow.
 *
 * Returned as part of the activity result intent data when the user dialog completes
 * successfully.
 *
 * @hide
 */
public class UserSelectionResult implements Parcelable {

    /**
    * The intent extra key for the {@code UserSelectionResult} object when the credential selector
    * activity finishes.
    */
    public static final String EXTRA_USER_SELECTION_RESULT =
            "android.credentials.ui.extra.USER_SELECTION_RESULT";

    @NonNull
    private final IBinder mRequestToken;

    @NonNull
    private final String mProviderId;

    // TODO: consider switching to string or other types, depending on the service implementation.
    private final int mEntryId;

    public UserSelectionResult(@NonNull IBinder requestToken, @NonNull String providerId,
            int entryId) {
        mRequestToken = requestToken;
        mProviderId = providerId;
        mEntryId = entryId;
    }

    /** Returns token of the app request that initiated this user dialog. */
    @NonNull
    public IBinder getRequestToken() {
        return mRequestToken;
    }

    /** Returns provider package name whose entry was selected by the user. */
    @NonNull
    public String getProviderId() {
        return mProviderId;
    }

    /** Returns the id of the visual entry that the user selected. */
    public int getEntryId() {
        return mEntryId;
    }

    protected UserSelectionResult(@NonNull Parcel in) {
        IBinder requestToken = in.readStrongBinder();
        String providerId = in.readString8();
        int entryId = in.readInt();

        mRequestToken = requestToken;
        AnnotationValidations.validate(NonNull.class, null, mRequestToken);
        mProviderId = providerId;
        AnnotationValidations.validate(NonNull.class, null, mProviderId);
        mEntryId = entryId;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mRequestToken);
        dest.writeString8(mProviderId);
        dest.writeInt(mEntryId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<UserSelectionResult> CREATOR =
            new Creator<UserSelectionResult>() {
        @Override
        public UserSelectionResult createFromParcel(@NonNull Parcel in) {
            return new UserSelectionResult(in);
        }

        @Override
        public UserSelectionResult[] newArray(int size) {
            return new UserSelectionResult[size];
        }
    };
}
