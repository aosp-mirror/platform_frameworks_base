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

import android.app.ClientTransactionHandler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Trace;

/**
 * Request to move an activity to started and visible state.
 * @hide
 */
public class StartActivityItem extends ActivityLifecycleItem {

    private static final String TAG = "StartActivityItem";

    @Override
    public void execute(ClientTransactionHandler client, IBinder token,
            PendingTransactionActions pendingActions) {
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "startActivityItem");
        client.handleStartActivity(token, pendingActions);
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }

    @Override
    public int getTargetState() {
        return ON_START;
    }


    // ObjectPoolItem implementation

    private StartActivityItem() {}

    /** Obtain an instance initialized with provided params. */
    public static StartActivityItem obtain() {
        StartActivityItem instance = ObjectPool.obtain(StartActivityItem.class);
        if (instance == null) {
            instance = new StartActivityItem();
        }

        return instance;
    }

    @Override
    public void recycle() {
        super.recycle();
        ObjectPool.recycle(this);
    }


    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        // Empty
    }

    /** Read from Parcel. */
    private StartActivityItem(Parcel in) {
        // Empty
    }

    public static final @android.annotation.NonNull Creator<StartActivityItem> CREATOR =
            new Creator<StartActivityItem>() {
                public StartActivityItem createFromParcel(Parcel in) {
                    return new StartActivityItem(in);
                }

                public StartActivityItem[] newArray(int size) {
                    return new StartActivityItem[size];
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
        return true;
    }

    @Override
    public int hashCode() {
        return 17;
    }

    @Override
    public String toString() {
        return "StartActivityItem{}";
    }
}

