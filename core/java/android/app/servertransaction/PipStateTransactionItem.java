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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.app.PictureInPictureUiState;
import android.os.IBinder;
import android.os.Parcel;

import java.util.Objects;

/**
 * Request an activity to enter picture-in-picture mode.
 * @hide
 */
public final class PipStateTransactionItem extends ActivityTransactionItem {

    private PictureInPictureUiState mPipState;

    @Override
    public void execute(@NonNull ClientTransactionHandler client, @NonNull ActivityClientRecord r,
            @NonNull PendingTransactionActions pendingActions) {
        client.handlePictureInPictureStateChanged(r, mPipState);
    }

    // ObjectPoolItem implementation

    private PipStateTransactionItem() {}

    /** Obtain an instance initialized with provided params. */
    @NonNull
    public static PipStateTransactionItem obtain(@NonNull IBinder activityToken,
            @NonNull PictureInPictureUiState pipState) {
        PipStateTransactionItem instance = ObjectPool.obtain(PipStateTransactionItem.class);
        if (instance == null) {
            instance = new PipStateTransactionItem();
        }
        instance.setActivityToken(activityToken);
        instance.mPipState = pipState;

        return instance;
    }

    @Override
    public void recycle() {
        super.recycle();
        mPipState = null;
        ObjectPool.recycle(this);
    }

    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        mPipState.writeToParcel(dest, flags);
    }

    /** Read from Parcel. */
    private PipStateTransactionItem(@NonNull Parcel in) {
        super(in);
        mPipState = PictureInPictureUiState.CREATOR.createFromParcel(in);
    }

    public static final @NonNull Creator<PipStateTransactionItem> CREATOR = new Creator<>() {
        public PipStateTransactionItem createFromParcel(@NonNull Parcel in) {
            return new PipStateTransactionItem(in);
        }

        public PipStateTransactionItem[] newArray(int size) {
            return new PipStateTransactionItem[size];
        }
    };

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!super.equals(o)) {
            return false;
        }
        final PipStateTransactionItem other = (PipStateTransactionItem) o;
        return Objects.equals(mPipState, other.mPipState);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + super.hashCode();
        result = 31 * result + Objects.hashCode(mPipState);
        return result;
    }

    @Override
    public String toString() {
        return "PipStateTransactionItem{" + super.toString() + "}";
    }
}
