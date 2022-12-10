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
import android.app.PendingIntent;
import android.app.slice.Slice;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.Objects;

/**
 * An action defined by the provider that intents into the provider's app for specific
 * user actions.
 */
public final class Action implements Parcelable {
    /** Slice object containing display content to be displayed with this action on the UI. */
    private final @NonNull Slice mSlice;
    /** The pending intent to be invoked when the user selects this action. */
    private final @NonNull PendingIntent mPendingIntent;

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
     * @param pendingIntent the intent to be invoked when the user selects this action
     */
    public Action(@NonNull Slice slice, @NonNull PendingIntent pendingIntent) {
        Objects.requireNonNull(slice, "slice must not be null");
        Objects.requireNonNull(pendingIntent, "pendingIntent must not be null");
        mSlice = slice;
        mPendingIntent = pendingIntent;
    }

    private Action(@NonNull Parcel in) {
        mSlice = in.readTypedObject(Slice.CREATOR);
        mPendingIntent = in.readTypedObject(PendingIntent.CREATOR);
    }

    public static final @NonNull Creator<Action> CREATOR = new Creator<Action>() {
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
        dest.writeTypedObject(mPendingIntent, flags);
    }

    /**
     * Returns a {@code Slice} object containing the display content to be displayed on the UI.
     */
    public @NonNull Slice getSlice() {
        return mSlice;
    }

    /**
     * Returns the {@link PendingIntent} to be invoked when the action is selected.
     */
    public @NonNull PendingIntent getPendingIntent() {
        return mPendingIntent;
    }
}
