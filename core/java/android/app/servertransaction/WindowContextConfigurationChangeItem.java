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

import static android.view.Display.INVALID_DISPLAY;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ClientTransactionHandler;
import android.content.res.Configuration;
import android.os.IBinder;
import android.os.Parcel;

import java.util.Objects;

/**
 * {@link android.window.WindowContext} configuration change message.
 * @hide
 */
public class WindowContextConfigurationChangeItem extends ClientTransactionItem {

    @Nullable
    private IBinder mClientToken;
    @Nullable
    private Configuration mConfiguration;
    private int mDisplayId;

    @Override
    public void execute(@NonNull ClientTransactionHandler client, @NonNull IBinder token,
            @NonNull PendingTransactionActions pendingActions) {
        client.handleWindowContextConfigurationChanged(mClientToken, mConfiguration, mDisplayId);
    }

    // ObjectPoolItem implementation

    private WindowContextConfigurationChangeItem() {}

    /** Obtains an instance initialized with provided params. */
    public static WindowContextConfigurationChangeItem obtain(
            @NonNull IBinder clientToken, @NonNull Configuration config, int displayId) {
        WindowContextConfigurationChangeItem instance =
                ObjectPool.obtain(WindowContextConfigurationChangeItem.class);
        if (instance == null) {
            instance = new WindowContextConfigurationChangeItem();
        }
        instance.mClientToken = requireNonNull(clientToken);
        instance.mConfiguration = requireNonNull(config);
        instance.mDisplayId = displayId;

        return instance;
    }

    @Override
    public void recycle() {
        mClientToken = null;
        mConfiguration = null;
        mDisplayId = INVALID_DISPLAY;
        ObjectPool.recycle(this);
    }

    // Parcelable implementation

    /** Writes to Parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mClientToken);
        dest.writeTypedObject(mConfiguration, flags);
        dest.writeInt(mDisplayId);
    }

    /** Reads from Parcel. */
    private WindowContextConfigurationChangeItem(@NonNull Parcel in) {
        mClientToken = in.readStrongBinder();
        mConfiguration = in.readTypedObject(Configuration.CREATOR);
        mDisplayId = in.readInt();
    }

    public static final @NonNull Creator<WindowContextConfigurationChangeItem> CREATOR =
            new Creator<>() {
                public WindowContextConfigurationChangeItem createFromParcel(Parcel in) {
                    return new WindowContextConfigurationChangeItem(in);
                }

                public WindowContextConfigurationChangeItem[] newArray(int size) {
                    return new WindowContextConfigurationChangeItem[size];
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
        final WindowContextConfigurationChangeItem other = (WindowContextConfigurationChangeItem) o;
        return Objects.equals(mClientToken, other.mClientToken)
                && Objects.equals(mConfiguration, other.mConfiguration)
                && mDisplayId == other.mDisplayId;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + Objects.hashCode(mClientToken);
        result = 31 * result + Objects.hashCode(mConfiguration);
        result = 31 * result + mDisplayId;
        return result;
    }

    @Override
    public String toString() {
        return "WindowContextConfigurationChangeItem{clientToken=" + mClientToken
                + ", config=" + mConfiguration
                + ", displayId=" + mDisplayId
                + "}";
    }
}
