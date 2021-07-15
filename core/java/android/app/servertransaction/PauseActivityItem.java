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
import android.annotation.Nullable;
import android.app.ActivityClient;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Trace;

/**
 * Request to move an activity to paused state.
 * @hide
 */
public class PauseActivityItem extends ActivityLifecycleItem {

    private static final String TAG = "PauseActivityItem";

    private boolean mFinished;
    private boolean mUserLeaving;
    private int mConfigChanges;
    private boolean mDontReport;

    @Override
    public void execute(ClientTransactionHandler client, ActivityClientRecord r,
            PendingTransactionActions pendingActions) {
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "activityPause");
        client.handlePauseActivity(r, mFinished, mUserLeaving, mConfigChanges, pendingActions,
                "PAUSE_ACTIVITY_ITEM");
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }

    @Override
    public int getTargetState() {
        return ON_PAUSE;
    }

    @Override
    public void postExecute(ClientTransactionHandler client, IBinder token,
            PendingTransactionActions pendingActions) {
        if (mDontReport) {
            return;
        }
        // TODO(lifecycler): Use interface callback instead of actual implementation.
        ActivityClient.getInstance().activityPaused(token);
    }


    // ObjectPoolItem implementation

    private PauseActivityItem() {}

    /** Obtain an instance initialized with provided params. */
    public static PauseActivityItem obtain(boolean finished, boolean userLeaving, int configChanges,
            boolean dontReport) {
        PauseActivityItem instance = ObjectPool.obtain(PauseActivityItem.class);
        if (instance == null) {
            instance = new PauseActivityItem();
        }
        instance.mFinished = finished;
        instance.mUserLeaving = userLeaving;
        instance.mConfigChanges = configChanges;
        instance.mDontReport = dontReport;

        return instance;
    }

    /** Obtain an instance initialized with default params. */
    public static PauseActivityItem obtain() {
        PauseActivityItem instance = ObjectPool.obtain(PauseActivityItem.class);
        if (instance == null) {
            instance = new PauseActivityItem();
        }
        instance.mFinished = false;
        instance.mUserLeaving = false;
        instance.mConfigChanges = 0;
        instance.mDontReport = true;

        return instance;
    }

    @Override
    public void recycle() {
        super.recycle();
        mFinished = false;
        mUserLeaving = false;
        mConfigChanges = 0;
        mDontReport = false;
        ObjectPool.recycle(this);
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

    public static final @NonNull Creator<PauseActivityItem> CREATOR =
            new Creator<PauseActivityItem>() {
        public PauseActivityItem createFromParcel(Parcel in) {
            return new PauseActivityItem(in);
        }

        public PauseActivityItem[] newArray(int size) {
            return new PauseActivityItem[size];
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

    @Override
    public String toString() {
        return "PauseActivityItem{finished=" + mFinished + ",userLeaving=" + mUserLeaving
                + ",configChanges=" + mConfigChanges + ",dontReport=" + mDontReport + "}";
    }
}
