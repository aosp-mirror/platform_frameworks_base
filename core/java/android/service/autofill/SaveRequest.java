/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.service.autofill;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;

/**
 * This class represents a request to an {@link AutofillService
 * autofill provider} to save applicable data entered by the user.
 *
 * @see AutofillService#onSaveRequest(SaveRequest, SaveCallback)
 */
public final class SaveRequest implements Parcelable {
    private final @NonNull ArrayList<FillContext> mFillContexts;
    private final @Nullable Bundle mClientState;

    /** @hide */
    public SaveRequest(@NonNull ArrayList<FillContext> fillContexts,
            @Nullable Bundle clientState) {
        mFillContexts = Preconditions.checkNotNull(fillContexts, "fillContexts");
        mClientState = clientState;
    }

    private SaveRequest(@NonNull Parcel parcel) {
        this(parcel.createTypedArrayList(FillContext.CREATOR), parcel.readBundle());
    }

    /**
     * @return The contexts associated with each previous fill request.
     */
    public @NonNull List<FillContext> getFillContexts() {
        return mFillContexts;
    }

    /**
     * Gets the extra client state returned from the last {@link
     * AutofillService#onFillRequest(FillRequest, android.os.CancellationSignal, FillCallback)}
     * fill request}.
     *
     * @return The client state.
     */
    public @Nullable Bundle getClientState() {
        return mClientState;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeTypedList(mFillContexts, flags);
        parcel.writeBundle(mClientState);
    }

    public static final Creator<SaveRequest> CREATOR =
            new Creator<SaveRequest>() {
        @Override
        public SaveRequest createFromParcel(Parcel parcel) {
            return new SaveRequest(parcel);
        }

        @Override
        public SaveRequest[] newArray(int size) {
            return new SaveRequest[size];
        }
    };
}
