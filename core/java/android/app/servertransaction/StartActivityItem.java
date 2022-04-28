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

import static android.os.Trace.TRACE_TAG_ACTIVITY_MANAGER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.os.Parcel;
import android.os.Trace;

/**
 * Request to move an activity to started and visible state.
 * @hide
 */
public class StartActivityItem extends ActivityLifecycleItem {

    private static final String TAG = "StartActivityItem";

    private ActivityOptions mActivityOptions;

    @Override
    public void execute(ClientTransactionHandler client, ActivityClientRecord r,
            PendingTransactionActions pendingActions) {
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "startActivityItem");
        client.handleStartActivity(r, pendingActions, mActivityOptions);
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }

    @Override
    public int getTargetState() {
        return ON_START;
    }


    // ObjectPoolItem implementation

    private StartActivityItem() {}

    /** Obtain an instance initialized with provided params. */
    public static StartActivityItem obtain(ActivityOptions activityOptions) {
        StartActivityItem instance = ObjectPool.obtain(StartActivityItem.class);
        if (instance == null) {
            instance = new StartActivityItem();
        }
        instance.mActivityOptions = activityOptions;

        return instance;
    }

    @Override
    public void recycle() {
        super.recycle();
        mActivityOptions = null;
        ObjectPool.recycle(this);
    }


    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBundle(mActivityOptions != null ? mActivityOptions.toBundle() : null);
    }

    /** Read from Parcel. */
    private StartActivityItem(Parcel in) {
        mActivityOptions = ActivityOptions.fromBundle(in.readBundle());
    }

    public static final @NonNull Creator<StartActivityItem> CREATOR =
            new Creator<StartActivityItem>() {
                public StartActivityItem createFromParcel(Parcel in) {
                    return new StartActivityItem(in);
                }

                public StartActivityItem[] newArray(int size) {
                    return new StartActivityItem[size];
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
        final StartActivityItem other = (StartActivityItem) o;
        return (mActivityOptions == null) == (other.mActivityOptions == null);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (mActivityOptions != null ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "StartActivityItem{options=" + mActivityOptions + "}";
    }
}

