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
import android.content.res.CompatibilityInfo;
import android.content.res.Configuration;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Trace;
import android.window.ActivityWindowInfo;

import java.util.Objects;

/**
 * Activity configuration changed callback.
 * @hide
 */
public class ActivityConfigurationChangeItem extends ActivityTransactionItem {

    private Configuration mConfiguration;
    private ActivityWindowInfo mActivityWindowInfo;

    @Override
    public void preExecute(@NonNull ClientTransactionHandler client) {
        CompatibilityInfo.applyOverrideScaleIfNeeded(mConfiguration);
        // Notify the client of an upcoming change in the token configuration. This ensures that
        // batches of config change items only process the newest configuration.
        client.updatePendingActivityConfiguration(getActivityToken(), mConfiguration);
    }

    @Override
    public void execute(@NonNull ClientTransactionHandler client, @NonNull ActivityClientRecord r,
            @NonNull PendingTransactionActions pendingActions) {
        // TODO(lifecycler): detect if PIP or multi-window mode changed and report it here.
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "activityConfigChanged");
        client.handleActivityConfigurationChanged(r, mConfiguration, INVALID_DISPLAY,
                mActivityWindowInfo);
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }

    // ObjectPoolItem implementation

    private ActivityConfigurationChangeItem() {}

    /** Obtain an instance initialized with provided params. */
    @NonNull
    public static ActivityConfigurationChangeItem obtain(@NonNull IBinder activityToken,
            @NonNull Configuration config, @NonNull ActivityWindowInfo activityWindowInfo) {
        ActivityConfigurationChangeItem instance =
                ObjectPool.obtain(ActivityConfigurationChangeItem.class);
        if (instance == null) {
            instance = new ActivityConfigurationChangeItem();
        }
        instance.setActivityToken(activityToken);
        instance.mConfiguration = new Configuration(config);
        instance.mActivityWindowInfo = new ActivityWindowInfo(activityWindowInfo);

        return instance;
    }

    @Override
    public void recycle() {
        super.recycle();
        mConfiguration = null;
        mActivityWindowInfo = null;
        ObjectPool.recycle(this);
    }


    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeTypedObject(mConfiguration, flags);
        dest.writeTypedObject(mActivityWindowInfo, flags);
    }

    /** Read from Parcel. */
    private ActivityConfigurationChangeItem(@NonNull Parcel in) {
        super(in);
        mConfiguration = in.readTypedObject(Configuration.CREATOR);
        mActivityWindowInfo = in.readTypedObject(ActivityWindowInfo.CREATOR);
    }

    public static final @NonNull Creator<ActivityConfigurationChangeItem> CREATOR =
            new Creator<>() {
                public ActivityConfigurationChangeItem createFromParcel(@NonNull Parcel in) {
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
        if (!super.equals(o)) {
            return false;
        }
        final ActivityConfigurationChangeItem other = (ActivityConfigurationChangeItem) o;
        return Objects.equals(mConfiguration, other.mConfiguration)
                && Objects.equals(mActivityWindowInfo, other.mActivityWindowInfo);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + super.hashCode();
        result = 31 * result + Objects.hashCode(mConfiguration);
        result = 31 * result + Objects.hashCode(mActivityWindowInfo);
        return result;
    }

    @Override
    public String toString() {
        return "ActivityConfigurationChange{" + super.toString()
                + ",config=" + mConfiguration
                + ",activityWindowInfo=" + mActivityWindowInfo + "}";
    }
}
