/*
 * Copyright 2021 The Android Open Source Project
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
import android.app.PictureInPictureUiState;
import android.os.Parcel;

/**
 * Request an activity to enter picture-in-picture mode.
 * @hide
 */
public final class PipStateTransactionItem extends ActivityTransactionItem {

    private PictureInPictureUiState mPipState;

    @Override
    public void execute(ClientTransactionHandler client, ActivityClientRecord r,
            PendingTransactionActions pendingActions) {
        client.handlePictureInPictureStateChanged(r, mPipState);
    }

    // ObjectPoolItem implementation

    private PipStateTransactionItem() {}

    /** Obtain an instance initialized with provided params. */
    public static PipStateTransactionItem obtain(PictureInPictureUiState pipState) {
        PipStateTransactionItem instance = ObjectPool.obtain(PipStateTransactionItem.class);
        if (instance == null) {
            instance = new PipStateTransactionItem();
        }
        instance.mPipState = pipState;

        return instance;
    }

    @Override
    public void recycle() {
        mPipState = null;
        ObjectPool.recycle(this);
    }

    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        mPipState.writeToParcel(dest, flags);
    }

    /** Read from Parcel. */
    private PipStateTransactionItem(Parcel in) {
        mPipState = PictureInPictureUiState.CREATOR.createFromParcel(in);
    }

    public static final @android.annotation.NonNull Creator<PipStateTransactionItem> CREATOR =
            new Creator<PipStateTransactionItem>() {
                public PipStateTransactionItem createFromParcel(Parcel in) {
                    return new PipStateTransactionItem(in);
                }

                public PipStateTransactionItem[] newArray(int size) {
                    return new PipStateTransactionItem[size];
                }
            };

    @Override
    public boolean equals(@Nullable Object o) {
        return this == o;
    }

    @Override
    public String toString() {
        return "PipStateTransactionItem{}";
    }
}
