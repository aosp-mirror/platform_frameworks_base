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

import android.app.PendingIntent;
import android.app.slice.Slice;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import java.util.Objects;

/**
 * An action defined by the provider that intents into the provider's app for specific
 * user actions.
 *
 * @hide
 */
public final class Action implements Parcelable {
    /** Info to be displayed with this action on the UI. */
    private final @NonNull Slice mInfo;
    /**
     * The pending intent to be invoked when the user selects this action.
     */
    private final @NonNull PendingIntent mPendingIntent;

    /**
     * Constructs an action to be displayed on the UI.
     *
     * @param actionInfo The info to be displayed along with this action.
     * @param pendingIntent The intent to be invoked when the user selects this action.
     * @throws NullPointerException If {@code actionInfo}, or {@code pendingIntent} is null.
     */
    public Action(@NonNull Slice actionInfo, @NonNull PendingIntent pendingIntent) {
        Objects.requireNonNull(actionInfo, "actionInfo must not be null");
        Objects.requireNonNull(pendingIntent, "pendingIntent must not be null");
        mInfo = actionInfo;
        mPendingIntent = pendingIntent;
    }

    private Action(@NonNull Parcel in) {
        mInfo = in.readParcelable(Slice.class.getClassLoader(), Slice.class);
        mPendingIntent = in.readParcelable(PendingIntent.class.getClassLoader(),
                PendingIntent.class);
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
        mInfo.writeToParcel(dest, flags);
        mPendingIntent.writeToParcel(dest, flags);
    }

    /**
     * Returns the action info as a {@link Slice} object, to be displayed on the UI.
     */
    public @NonNull Slice getActionInfo() {
        return mInfo;
    }

    /**
     * Returns the {@link PendingIntent} to be invoked when the action is selected.
     */
    public @NonNull PendingIntent getPendingIntent() {
        return mPendingIntent;
    }
}
