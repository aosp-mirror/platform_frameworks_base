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

import static android.os.Trace.TRACE_TAG_ACTIVITY_MANAGER;
import static android.view.Display.INVALID_DISPLAY;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.content.res.Configuration;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Trace;

import java.util.Objects;

/**
 * Activity configuration changed callback.
 * @hide
 */
public class ActivityConfigurationChangeItem extends ActivityTransactionItem {

    private Configuration mConfiguration;

    @Override
    public void preExecute(android.app.ClientTransactionHandler client, IBinder token) {
        // Notify the client of an upcoming change in the token configuration. This ensures that
        // batches of config change items only process the newest configuration.
        client.updatePendingActivityConfiguration(token, mConfiguration);
    }

    @Override
    public void execute(ClientTransactionHandler client, ActivityClientRecord r,
            PendingTransactionActions pendingActions) {
        // TODO(lifecycler): detect if PIP or multi-window mode changed and report it here.
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "activityConfigChanged");
        client.handleActivityConfigurationChanged(r, mConfiguration, INVALID_DISPLAY);
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }


    // ObjectPoolItem implementation

    private ActivityConfigurationChangeItem() {}

    /** Obtain an instance initialized with provided params. */
    public static ActivityConfigurationChangeItem obtain(@NonNull Configuration config) {
        if (config == null) {
            throw new IllegalArgumentException("Config must not be null.");
        }

        ActivityConfigurationChangeItem instance =
                ObjectPool.obtain(ActivityConfigurationChangeItem.class);
        if (instance == null) {
            instance = new ActivityConfigurationChangeItem();
        }
        instance.mConfiguration = config;

        return instance;
    }

    @Override
    public void recycle() {
        mConfiguration = Configuration.EMPTY;
        ObjectPool.recycle(this);
    }


    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedObject(mConfiguration, flags);
    }

    /** Read from Parcel. */
    private ActivityConfigurationChangeItem(Parcel in) {
        mConfiguration = in.readTypedObject(Configuration.CREATOR);
    }

    public static final @NonNull Creator<ActivityConfigurationChangeItem> CREATOR =
            new Creator<ActivityConfigurationChangeItem>() {
        public ActivityConfigurationChangeItem createFromParcel(Parcel in) {
            return new ActivityConfigurationChangeItem(in);
        }

        public ActivityConfigurationChangeItem[] newArray(int size) {
            return new ActivityConfigurationChangeItem[size];
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
        final ActivityConfigurationChangeItem other = (ActivityConfigurationChangeItem) o;
        return Objects.equals(mConfiguration, other.mConfiguration);
    }

    @Override
    public int hashCode() {
        return mConfiguration.hashCode();
    }

    @Override
    public String toString() {
        return "ActivityConfigurationChange{config=" + mConfiguration + "}";
    }
}
