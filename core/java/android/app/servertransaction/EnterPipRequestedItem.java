/*
 * Copyright 2019 The Android Open Source Project
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

package android.app.servertransaction;

import android.annotation.NonNull;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.os.IBinder;
import android.os.Parcel;

/**
 * Request an activity to enter picture-in-picture mode.
 *
 * @hide
 */
public final class EnterPipRequestedItem extends ActivityTransactionItem {

    public EnterPipRequestedItem(@NonNull IBinder activityToken) {
        super(activityToken);
    }

    @Override
    public void execute(@NonNull ClientTransactionHandler client, @NonNull ActivityClientRecord r,
            @NonNull PendingTransactionActions pendingActions) {
        client.handlePictureInPictureRequested(r);
    }

    // Parcelable implementation

    /** Reads from Parcel. */
    private EnterPipRequestedItem(@NonNull Parcel in) {
        super(in);
    }

    public static final @NonNull Creator<EnterPipRequestedItem> CREATOR =
            new Creator<>() {
                public EnterPipRequestedItem createFromParcel(@NonNull Parcel in) {
                    return new EnterPipRequestedItem(in);
                }

                public EnterPipRequestedItem[] newArray(int size) {
                    return new EnterPipRequestedItem[size];
                }
            };

    @Override
    public String toString() {
        return "EnterPipRequestedItem{" + super.toString() + "}";
    }
}
