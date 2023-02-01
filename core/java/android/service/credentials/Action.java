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
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.app.slice.Slice;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * An action defined by the provider that intents into the provider's app for specific
 * user actions.
 *
 * <p>If user selects this action entry, the corresponding {@link PendingIntent} set on the
 * {@code slice} as a {@link androidx.slice.core.SliceAction} will get invoked.
 *
 * <p>Any class that derives this class must only add extra field values to the {@code slice}
 * object passed into the constructor. Any other field will not be parceled through. If the
 * derived class has custom parceling implementation, this class will not be able to unpack
 * the parcel without having access to that implementation.
 */
@SuppressLint("ParcelNotFinal")
public class Action implements Parcelable {
    /** Slice object containing display content to be displayed with this action on the UI. */
    @NonNull
    private final Slice mSlice;

    /**
     * Constructs an action to be displayed on the UI.
     *
     * <p> Actions must be used for any provider related operations, such as opening the provider
     * app, intenting straight into certain app activities like 'manage credentials', top
     * level authentication before displaying any content etc.
     *
     * <p> See details on usage of {@code Action} for various actionable entries in
     * {@link BeginCreateCredentialResponse} and {@link BeginGetCredentialResponse}.
     *
     * @param slice the display content to be displayed on the UI, along with this action
     */
    public Action(@NonNull Slice slice) {
        Objects.requireNonNull(slice, "slice must not be null");
        mSlice = slice;
    }

    private Action(@NonNull Parcel in) {
        mSlice = in.readTypedObject(Slice.CREATOR);
    }

    @NonNull
    public static final Creator<Action> CREATOR = new Creator<Action>() {
        @Override
        public Action createFromParcel(@NonNull Parcel in) {
            return new Action(in);
        }

        @Override
        public Action[] newArray(int size) {
            return new Action[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mSlice, flags);
    }

    /**
     * Returns a {@code Slice} object containing the display content to be displayed on the UI.
     */
    @NonNull
    public Slice getSlice() {
        return mSlice;
    }
}
