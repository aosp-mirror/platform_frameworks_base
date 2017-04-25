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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.assist.AssistStructure;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Parcel;
import android.os.Parcelable;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * This class represents a request to an {@link AutofillService autofill provider}
 * to interpret the screen and provide information to the system which views are
 * interesting for saving and what are the possible ways to fill the inputs on
 * the screen if applicable.
 *
 * @see AutofillService#onFillRequest(FillRequest, CancellationSignal, FillCallback)
 */
public final class FillRequest implements Parcelable {
    /**
     * Indicates autofill was explicitly requested by the user.
     */
    public static final int FLAG_MANUAL_REQUEST = 0x1;

    /** @hide */
    public static final int INVALID_REQUEST_ID = Integer.MIN_VALUE;

    /** @hide */
    @IntDef(
        flag = true,
        value = {FLAG_MANUAL_REQUEST})
    @Retention(RetentionPolicy.SOURCE)
    @interface RequestFlags{}

    private final int mId;
    private final @RequestFlags int mFlags;
    private final @NonNull AssistStructure mStructure;
    private final @Nullable Bundle mClientState;

    private FillRequest(@NonNull Parcel parcel) {
        mId = parcel.readInt();
        mStructure = parcel.readParcelable(null);
        mClientState = parcel.readBundle();
        mFlags = parcel.readInt();
    }

    /** @hide */
    public FillRequest(int id, @NonNull AssistStructure structure,
            @Nullable Bundle clientState, @RequestFlags int flags) {
        mId = id;
        mFlags = Preconditions.checkFlagsArgument(flags, FLAG_MANUAL_REQUEST);
        mStructure = Preconditions.checkNotNull(structure, "structure");
        mClientState = clientState;
    }

    /**
     * @return The unique id of this request.
     */
    public int getId() {
        return mId;
    }

    /**
     * @return The flags associated with this request.
     *
     * @see #FLAG_MANUAL_REQUEST
     */
    public @RequestFlags int getFlags() {
        return mFlags;
    }

    /**
     * @return The structure capturing the UI state.
     */
    public @NonNull AssistStructure getStructure() {
        return mStructure;
    }

    /**
     * Gets the extra client state returned from the last {@link
     * AutofillService#onFillRequest(FillRequest, CancellationSignal, FillCallback)
     * fill request}.
     * <p>
     * Once a {@link AutofillService#onSaveRequest(SaveRequest, SaveCallback)
     * save request} is made the client state is cleared.
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
        parcel.writeInt(mId);
        parcel.writeParcelable(mStructure, flags);
        parcel.writeBundle(mClientState);
        parcel.writeInt(mFlags);
    }

    public static final Parcelable.Creator<FillRequest> CREATOR =
            new Parcelable.Creator<FillRequest>() {
        @Override
        public FillRequest createFromParcel(Parcel parcel) {
            return new FillRequest(parcel);
        }

        @Override
        public FillRequest[] newArray(int size) {
            return new FillRequest[size];
        }
    };
}
