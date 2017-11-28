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
 * Request to move an activity to paused state.
 * @hide
 */
public class PauseActivityItem extends ActivityLifecycleItem {

    private static final String TAG = "PauseActivityItem";

    private final boolean mFinished;
    private final boolean mUserLeaving;
    private final int mConfigChanges;
    private final boolean mDontReport;

    private int mLifecycleSeq;

    public PauseActivityItem(boolean finished, boolean userLeaving, int configChanges,
            boolean dontReport) {
        mFinished = finished;
        mUserLeaving = userLeaving;
        mConfigChanges = configChanges;
        mDontReport = dontReport;
    }

    @Override
    public void prepare(ClientTransactionHandler client, IBinder token) {
        mLifecycleSeq = client.getLifecycleSeq();
        if (DEBUG_ORDER) {
            Slog.d(TAG, "Pause transaction for " + client + " received seq: "
                    + mLifecycleSeq);
        }
    }

    @Override
    public void execute(ClientTransactionHandler client, IBinder token) {
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "activityPause");
        client.handlePauseActivity(token, mFinished, mUserLeaving, mConfigChanges, mDontReport,
                mLifecycleSeq);
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }

    @Override
    public int getTargetState() {
        return PAUSED;
    }


    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBoolean(mFinished);
        dest.writeBoolean(mUserLeaving);
        dest.writeInt(mConfigChanges);
        dest.writeBoolean(mDontReport);
    }

    /** Read from Parcel. */
    private PauseActivityItem(Parcel in) {
        mFinished = in.readBoolean();
        mUserLeaving = in.readBoolean();
        mConfigChanges = in.readInt();
        mDontReport = in.readBoolean();
    }

    public static final Creator<PauseActivityItem> CREATOR =
            new Creator<PauseActivityItem>() {
        public PauseActivityItem createFromParcel(Parcel in) {
            return new PauseActivityItem(in);
        }

        public PauseActivityItem[] newArray(int size) {
            return new PauseActivityItem[size];
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
        final PauseActivityItem other = (PauseActivityItem) o;
        return mFinished == other.mFinished && mUserLeaving == other.mUserLeaving
                && mConfigChanges == other.mConfigChanges && mDontReport == other.mDontReport;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (mFinished ? 1 : 0);
        result = 31 * result + (mUserLeaving ? 1 : 0);
        result = 31 * result + mConfigChanges;
        result = 31 * result + (mDontReport ? 1 : 0);
        return result;
    }
}
