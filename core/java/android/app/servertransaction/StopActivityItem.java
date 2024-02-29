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

import android.annotation.NonNull;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Trace;

/**
 * Request to move an activity to stopped state.
 * @hide
 */
public class StopActivityItem extends ActivityLifecycleItem {

    private static final String TAG = "StopActivityItem";

    @Override
    public void execute(@NonNull ClientTransactionHandler client, @NonNull ActivityClientRecord r,
            @NonNull PendingTransactionActions pendingActions) {
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "activityStop");
        client.handleStopActivity(r, pendingActions,
                true /* finalStateRequest */, "STOP_ACTIVITY_ITEM");
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }

    @Override
    public void postExecute(@NonNull ClientTransactionHandler client,
            @NonNull PendingTransactionActions pendingActions) {
        client.reportStop(pendingActions);
    }

    @Override
    public int getTargetState() {
        return ON_STOP;
    }

    // ObjectPoolItem implementation

    private StopActivityItem() {}

    /**
     * Obtain an instance initialized with provided params.
     * @param activityToken the activity that stops.
     */
    @NonNull
    public static StopActivityItem obtain(@NonNull IBinder activityToken) {
        StopActivityItem instance = ObjectPool.obtain(StopActivityItem.class);
        if (instance == null) {
            instance = new StopActivityItem();
        }
        instance.setActivityToken(activityToken);

        return instance;
    }

    @Override
    public void recycle() {
        super.recycle();
        ObjectPool.recycle(this);
    }

    // Parcelable implementation

    /** Read from Parcel. */
    private StopActivityItem(@NonNull Parcel in) {
        super(in);
    }

    public static final @NonNull Creator<StopActivityItem> CREATOR = new Creator<>() {
        public StopActivityItem createFromParcel(@NonNull Parcel in) {
            return new StopActivityItem(in);
        }

        public StopActivityItem[] newArray(int size) {
            return new StopActivityItem[size];
        }
    };

    @Override
    public String toString() {
        return "StopActivityItem{" + super.toString() + "}";
    }
}
