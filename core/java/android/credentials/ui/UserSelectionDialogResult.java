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
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;

/**
 * Result data matching {@link BaseDialogResult#RESULT_CODE_DIALOG_COMPLETE_WITH_SELECTION}.
 *
 * @hide
 */
public final class UserSelectionDialogResult extends BaseDialogResult implements Parcelable {
    /** Parses and returns a UserSelectionDialogResult from the given resultData. */
    @Nullable
    public static UserSelectionDialogResult fromResultData(@NonNull Bundle resultData) {
        return resultData.getParcelable(
            EXTRA_USER_SELECTION_RESULT, UserSelectionDialogResult.class);
    }

    /**
     * Used for the UX to construct the {@code resultData Bundle} to send via the {@code
     *  ResultReceiver}.
     */
    public static void addToBundle(
            @NonNull UserSelectionDialogResult result, @NonNull Bundle bundle) {
        bundle.putParcelable(EXTRA_USER_SELECTION_RESULT, result);
    }

    /**
     * The intent extra key for the {@code UserSelectionDialogResult} object when the credential
     * selector activity finishes.
     */
    private static final String EXTRA_USER_SELECTION_RESULT =
            "android.credentials.ui.extra.USER_SELECTION_RESULT";

    @NonNull private final String mProviderId;
    @NonNull private final String mEntryKey;
    @NonNull private final String mEntrySubkey;
    @Nullable private ProviderPendingIntentResponse mProviderPendingIntentResponse;

    public UserSelectionDialogResult(
            @NonNull IBinder requestToken, @NonNull String providerId,
            @NonNull String entryKey, @NonNull String entrySubkey) {
        super(requestToken);
        mProviderId = providerId;
        mEntryKey = entryKey;
        mEntrySubkey = entrySubkey;
    }

    public UserSelectionDialogResult(
            @NonNull IBinder requestToken, @NonNull String providerId,
            @NonNull String entryKey, @NonNull String entrySubkey,
            @Nullable ProviderPendingIntentResponse providerPendingIntentResponse) {
        super(requestToken);
        mProviderId = providerId;
        mEntryKey = entryKey;
        mEntrySubkey = entrySubkey;
        mProviderPendingIntentResponse = providerPendingIntentResponse;
    }

    /** Returns provider package name whose entry was selected by the user. */
    @NonNull
    public String getProviderId() {
        return mProviderId;
    }

    /** Returns the key of the visual entry that the user selected. */
    @NonNull
    public String getEntryKey() {
        return mEntryKey;
    }

    /** Returns the subkey of the visual entry that the user selected. */
    @NonNull
    public String getEntrySubkey() {
        return mEntrySubkey;
    }

    /** Returns the pending intent response from the provider. */
    @Nullable
    public ProviderPendingIntentResponse getPendingIntentProviderResponse() {
        return mProviderPendingIntentResponse;
    }

    protected UserSelectionDialogResult(@NonNull Parcel in) {
        super(in);
        String providerId = in.readString8();
        String entryKey = in.readString8();
        String entrySubkey = in.readString8();

        mProviderId = providerId;
        AnnotationValidations.validate(NonNull.class, null, mProviderId);
        mEntryKey = entryKey;
        AnnotationValidations.validate(NonNull.class, null, mEntryKey);
        mEntrySubkey = entrySubkey;
        AnnotationValidations.validate(NonNull.class, null, mEntrySubkey);
        mProviderPendingIntentResponse = in.readTypedObject(ProviderPendingIntentResponse.CREATOR);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeString8(mProviderId);
        dest.writeString8(mEntryKey);
        dest.writeString8(mEntrySubkey);
        dest.writeTypedObject(mProviderPendingIntentResponse, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final @NonNull Creator<UserSelectionDialogResult> CREATOR =
            new Creator<UserSelectionDialogResult>() {
        @Override
        public UserSelectionDialogResult createFromParcel(@NonNull Parcel in) {
            return new UserSelectionDialogResult(in);
        }

        @Override
        public UserSelectionDialogResult[] newArray(int size) {
            return new UserSelectionDialogResult[size];
        }
    };
}
