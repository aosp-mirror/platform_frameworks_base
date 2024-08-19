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
import android.content.res.Configuration;
import android.os.IBinder;
import android.os.Parcel;
import android.window.WindowContextInfo;

import java.util.Objects;

/**
 * {@link android.window.WindowContext} configuration change message.
 *
 * @hide
 */
public class WindowContextInfoChangeItem extends ClientTransactionItem {

    @NonNull
    private final IBinder mClientToken;

    @NonNull
    private final WindowContextInfo mInfo;

    public WindowContextInfoChangeItem(
            @NonNull IBinder clientToken, @NonNull Configuration config, int displayId) {
        mClientToken = requireNonNull(clientToken);
        mInfo = new WindowContextInfo(new Configuration(config), displayId);
    }

    @Override
    public void execute(@NonNull ClientTransactionHandler client,
            @NonNull PendingTransactionActions pendingActions) {
        client.handleWindowContextInfoChanged(mClientToken, mInfo);
    }

    // Parcelable implementation

    /** Writes to Parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mClientToken);
        dest.writeTypedObject(mInfo, flags);
    }

    /** Reads from Parcel. */
    private WindowContextInfoChangeItem(@NonNull Parcel in) {
        mClientToken = in.readStrongBinder();
        mInfo = requireNonNull(in.readTypedObject(WindowContextInfo.CREATOR));
    }

    public static final @NonNull Creator<WindowContextInfoChangeItem> CREATOR =
            new Creator<>() {
                public WindowContextInfoChangeItem createFromParcel(Parcel in) {
                    return new WindowContextInfoChangeItem(in);
                }

                public WindowContextInfoChangeItem[] newArray(int size) {
                    return new WindowContextInfoChangeItem[size];
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
        final WindowContextInfoChangeItem other = (WindowContextInfoChangeItem) o;
        return Objects.equals(mClientToken, other.mClientToken)
                && Objects.equals(mInfo, other.mInfo);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + Objects.hashCode(mClientToken);
        result = 31 * result + Objects.hashCode(mInfo);
        return result;
    }

    @Override
    public String toString() {
        return "WindowContextInfoChangeItem{clientToken=" + mClientToken
                + ", info=" + mInfo
                + "}";
    }
}
