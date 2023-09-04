/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ClientTransactionHandler;
import android.os.IBinder;
import android.os.Parcel;

import java.util.Objects;

/**
 * {@link android.window.WindowContext} window removal message.
 * @hide
 */
public class WindowContextWindowRemovalItem extends ClientTransactionItem {

    @Nullable
    private IBinder mClientToken;

    @Override
    public void execute(@NonNull ClientTransactionHandler client,
            @NonNull PendingTransactionActions pendingActions) {
        client.handleWindowContextWindowRemoval(mClientToken);
    }

    // ObjectPoolItem implementation

    private WindowContextWindowRemovalItem() {}

    /** Obtains an instance initialized with provided params. */
    public static WindowContextWindowRemovalItem obtain(@NonNull IBinder clientToken) {
        WindowContextWindowRemovalItem instance =
                ObjectPool.obtain(WindowContextWindowRemovalItem.class);
        if (instance == null) {
            instance = new WindowContextWindowRemovalItem();
        }
        instance.mClientToken = requireNonNull(clientToken);

        return instance;
    }

    @Override
    public void recycle() {
        mClientToken = null;
        ObjectPool.recycle(this);
    }

    // Parcelable implementation

    /** Writes to Parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mClientToken);
    }

    /** Reads from Parcel. */
    private WindowContextWindowRemovalItem(@NonNull Parcel in) {
        mClientToken = in.readStrongBinder();
    }

    public static final @NonNull Creator<WindowContextWindowRemovalItem> CREATOR = new Creator<>() {
        public WindowContextWindowRemovalItem createFromParcel(Parcel in) {
            return new WindowContextWindowRemovalItem(in);
        }

        public WindowContextWindowRemovalItem[] newArray(int size) {
            return new WindowContextWindowRemovalItem[size];
        }
    };

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final WindowContextWindowRemovalItem other = (WindowContextWindowRemovalItem) o;
        return Objects.equals(mClientToken, other.mClientToken);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + Objects.hashCode(mClientToken);
        return result;
    }

    @Override
    public String toString() {
        return "WindowContextWindowRemovalItem{clientToken=" + mClientToken + "}";
    }
}
