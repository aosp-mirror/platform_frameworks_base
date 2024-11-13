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
 *
 * @hide
 */
public class PauseActivityItem extends ActivityLifecycleItem {

    private final boolean mFinished;
    private final boolean mUserLeaving;
    private final boolean mDontReport;
    private final boolean mAutoEnteringPip;

    public PauseActivityItem(@NonNull IBinder activityToken) {
        this(activityToken, false /* finished */, false /* userLeaving */,
                true /* dontReport */, false /* autoEnteringPip*/);
    }

    public PauseActivityItem(@NonNull IBinder activityToken, boolean finished,
            boolean userLeaving, boolean dontReport, boolean autoEnteringPip) {
        super(activityToken);
        mFinished = finished;
        mUserLeaving = userLeaving;
        mDontReport = dontReport;
        mAutoEnteringPip = autoEnteringPip;
    }

    @Override
    public void execute(@NonNull ClientTransactionHandler client, @NonNull ActivityClientRecord r,
            @NonNull PendingTransactionActions pendingActions) {
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "activityPause");
        client.handlePauseActivity(r, mFinished, mUserLeaving, mAutoEnteringPip,
                pendingActions, "PAUSE_ACTIVITY_ITEM");
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }

    @Override
    public int getTargetState() {
        return ON_PAUSE;
    }

    @Override
    public void postExecute(@NonNull ClientTransactionHandler client,
            @NonNull PendingTransactionActions pendingActions) {
        if (mDontReport) {
            return;
        }
        // TODO(lifecycler): Use interface callback instead of actual implementation.
        ActivityClient.getInstance().activityPaused(getActivityToken());
    }

    // Parcelable implementation

    /** Writes to Parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeBoolean(mFinished);
        dest.writeBoolean(mUserLeaving);
        dest.writeBoolean(mDontReport);
        dest.writeBoolean(mAutoEnteringPip);
    }

    /** Reads from Parcel. */
    private PauseActivityItem(@NonNull Parcel in) {
        super(in);
        mFinished = in.readBoolean();
        mUserLeaving = in.readBoolean();
        mDontReport = in.readBoolean();
        mAutoEnteringPip = in.readBoolean();
    }

    public static final @NonNull Creator<PauseActivityItem> CREATOR = new Creator<>() {
        public PauseActivityItem createFromParcel(@NonNull Parcel in) {
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
        if (!super.equals(o)) {
            return false;
        }
        final PauseActivityItem other = (PauseActivityItem) o;
        return mFinished == other.mFinished && mUserLeaving == other.mUserLeaving
                && mDontReport == other.mDontReport
                && mAutoEnteringPip == other.mAutoEnteringPip;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + super.hashCode();
        result = 31 * result + (mFinished ? 1 : 0);
        result = 31 * result + (mUserLeaving ? 1 : 0);
        result = 31 * result + (mDontReport ? 1 : 0);
        result = 31 * result + (mAutoEnteringPip ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "PauseActivityItem{" + super.toString()
                + ",finished=" + mFinished
                + ",userLeaving=" + mUserLeaving
                + ",dontReport=" + mDontReport
                + ",autoEnteringPip=" + mAutoEnteringPip + "}";
    }
}
