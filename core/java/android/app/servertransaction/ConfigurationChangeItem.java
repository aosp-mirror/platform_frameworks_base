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

import android.app.ClientTransactionHandler;
import android.content.res.Configuration;
import android.os.IBinder;
import android.os.Parcel;

import java.util.Objects;

/**
 * App configuration change message.
 * @hide
 */
public class ConfigurationChangeItem extends ClientTransactionItem {

    private Configuration mConfiguration;

    @Override
    public void preExecute(android.app.ClientTransactionHandler client, IBinder token) {
        client.updatePendingConfiguration(mConfiguration);
    }

    @Override
    public void execute(ClientTransactionHandler client, IBinder token,
            PendingTransactionActions pendingActions) {
        client.handleConfigurationChanged(mConfiguration);
    }


    // ObjectPoolItem implementation

    private ConfigurationChangeItem() {}

    /** Obtain an instance initialized with provided params. */
    public static ConfigurationChangeItem obtain(Configuration config) {
        ConfigurationChangeItem instance = ObjectPool.obtain(ConfigurationChangeItem.class);
        if (instance == null) {
            instance = new ConfigurationChangeItem();
        }
        instance.mConfiguration = config;

        return instance;
    }

    @Override
    public void recycle() {
        mConfiguration = null;
        ObjectPool.recycle(this);
    }


    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedObject(mConfiguration, flags);
    }

    /** Read from Parcel. */
    private ConfigurationChangeItem(Parcel in) {
        mConfiguration = in.readTypedObject(Configuration.CREATOR);
    }

    public static final @android.annotation.NonNull Creator<ConfigurationChangeItem> CREATOR =
            new Creator<ConfigurationChangeItem>() {
        public ConfigurationChangeItem createFromParcel(Parcel in) {
            return new ConfigurationChangeItem(in);
        }

        public ConfigurationChangeItem[] newArray(int size) {
            return new ConfigurationChangeItem[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ConfigurationChangeItem other = (ConfigurationChangeItem) o;
        return Objects.equals(mConfiguration, other.mConfiguration);
    }

    @Override
    public int hashCode() {
        return mConfiguration.hashCode();
    }

    @Override
    public String toString() {
        return "ConfigurationChangeItem{config=" + mConfiguration + "}";
    }
}
