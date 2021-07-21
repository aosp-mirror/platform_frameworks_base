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

import android.annotation.Nullable;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.os.Parcel;

/**
 * Request an activity to enter picture-in-picture mode.
 * @hide
 */
public final class EnterPipRequestedItem extends ActivityTransactionItem {

    @Override
    public void execute(ClientTransactionHandler client, ActivityClientRecord r,
            PendingTransactionActions pendingActions) {
        client.handlePictureInPictureRequested(r);
    }

    // ObjectPoolItem implementation

    private EnterPipRequestedItem() {}

    /** Obtain an instance initialized with provided params. */
    public static EnterPipRequestedItem obtain() {
        EnterPipRequestedItem instance = ObjectPool.obtain(EnterPipRequestedItem.class);
        if (instance == null) {
            instance = new EnterPipRequestedItem();
        }
        return instance;
    }

    @Override
    public void recycle() {
        ObjectPool.recycle(this);
    }

    // Parcelable implementation

    @Override
    public void writeToParcel(Parcel dest, int flags) { }

    public static final @android.annotation.NonNull Creator<EnterPipRequestedItem> CREATOR =
            new Creator<EnterPipRequestedItem>() {
                public EnterPipRequestedItem createFromParcel(Parcel in) {
                    return new EnterPipRequestedItem();
                }

                public EnterPipRequestedItem[] newArray(int size) {
                    return new EnterPipRequestedItem[size];
                }
            };

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o;
    }

    @Override
    public String toString() {
        return "EnterPipRequestedItem{}";
    }
}
