/*
 * Copyright 2017 The Android Open Source Project
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
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.Parcel;

import java.util.Objects;

/**
 * App configuration change message.
 *
 * @hide
 */
public class ConfigurationChangeItem extends ClientTransactionItem {

    @NonNull
    private final Configuration mConfiguration;

    private final int mDeviceId;

    public ConfigurationChangeItem(@NonNull Configuration config, int deviceId) {
        mConfiguration = new Configuration(config);
        mDeviceId = deviceId;
    }

    @Override
    public void preExecute(@NonNull ClientTransactionHandler client) {
        CompatibilityInfo.applyOverrideIfNeeded(mConfiguration);
        client.updatePendingConfiguration(mConfiguration);
    }

    @Override
    public void execute(@NonNull ClientTransactionHandler client,
            @NonNull PendingTransactionActions pendingActions) {
        client.handleConfigurationChanged(mConfiguration, mDeviceId);
    }

    // Parcelable implementation

    /** Writes to Parcel. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedObject(mConfiguration, flags);
        dest.writeInt(mDeviceId);
    }

    /** Reads from Parcel. */
    private ConfigurationChangeItem(Parcel in) {
        mConfiguration = requireNonNull(in.readTypedObject(Configuration.CREATOR));
        mDeviceId = in.readInt();
    }

    public static final @android.annotation.NonNull Creator<ConfigurationChangeItem> CREATOR =
            new Creator<>() {
                public ConfigurationChangeItem createFromParcel(Parcel in) {
                    return new ConfigurationChangeItem(in);
                }

                public ConfigurationChangeItem[] newArray(int size) {
                    return new ConfigurationChangeItem[size];
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
        final ConfigurationChangeItem other = (ConfigurationChangeItem) o;
        return Objects.equals(mConfiguration, other.mConfiguration)
                && mDeviceId == other.mDeviceId;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + mDeviceId;
        result = 31 * result + mConfiguration.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "ConfigurationChangeItem{deviceId=" + mDeviceId + ", config" + mConfiguration + "}";
    }
}
