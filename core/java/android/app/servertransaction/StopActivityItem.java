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

import android.app.ClientTransactionHandler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Trace;
import android.util.Slog;

/**
 * Request to move an activity to stopped state.
 * @hide
 */
public class StopActivityItem extends ActivityLifecycleItem {

    private static final String TAG = "StopActivityItem";

    private final boolean mShowWindow;
    private final int mConfigChanges;

    private int mLifecycleSeq;

    public StopActivityItem(boolean showWindow, int configChanges) {
        mShowWindow = showWindow;
        mConfigChanges = configChanges;
    }

    @Override
    public void prepare(ClientTransactionHandler client, IBinder token) {
        mLifecycleSeq = client.getLifecycleSeq();
        if (DEBUG_ORDER) {
            Slog.d(TAG, "Stop transaction for " + client + " received seq: "
                    + mLifecycleSeq);
        }
    }

    @Override
    public void execute(ClientTransactionHandler client, IBinder token) {
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "activityStop");
        client.handleStopActivity(token, mShowWindow, mConfigChanges, mLifecycleSeq);
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }

    @Override
    public int getTargetState() {
        return STOPPED;
    }


    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBoolean(mShowWindow);
        dest.writeInt(mConfigChanges);
    }

    /** Read from Parcel. */
    private StopActivityItem(Parcel in) {
        mShowWindow = in.readBoolean();
        mConfigChanges = in.readInt();
    }

    public static final Creator<StopActivityItem> CREATOR =
            new Creator<StopActivityItem>() {
        public StopActivityItem createFromParcel(Parcel in) {
            return new StopActivityItem(in);
        }

        public StopActivityItem[] newArray(int size) {
            return new StopActivityItem[size];
        }
    };
}
