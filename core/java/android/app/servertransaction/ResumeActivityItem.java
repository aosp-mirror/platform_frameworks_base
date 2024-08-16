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
import android.app.ActivityManager;
import android.app.ActivityManager.ProcessState;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Trace;

/**
 * Request to move an activity to resumed state.
 *
 * @hide
 */
public class ResumeActivityItem extends ActivityLifecycleItem {

    @ProcessState
    private final int mProcState;

    private final boolean mIsForward;

    // Whether we should send compat fake focus when the activity is resumed. This is needed
    // because some game engines wait to get focus before drawing the content of the app.
    private final boolean mShouldSendCompatFakeFocus;

    public ResumeActivityItem(@NonNull IBinder activityToken, boolean isForward,
            boolean shouldSendCompatFakeFocus) {
        this(activityToken, ActivityManager.PROCESS_STATE_UNKNOWN, isForward,
                shouldSendCompatFakeFocus);
    }

    public ResumeActivityItem(@NonNull IBinder activityToken, @ProcessState int procState,
            boolean isForward, boolean shouldSendCompatFakeFocus) {
        super(activityToken);
        mProcState = procState;
        mIsForward = isForward;
        mShouldSendCompatFakeFocus = shouldSendCompatFakeFocus;
    }

    @Override
    public void preExecute(@NonNull ClientTransactionHandler client) {
        if (mProcState != ActivityManager.PROCESS_STATE_UNKNOWN) {
            client.updateProcessState(mProcState, false);
        }
    }

    @Override
    public void execute(@NonNull ClientTransactionHandler client, @NonNull ActivityClientRecord r,
            @NonNull PendingTransactionActions pendingActions) {
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "activityResume");
        client.handleResumeActivity(r, true /* finalStateRequest */, mIsForward,
                mShouldSendCompatFakeFocus, "RESUME_ACTIVITY");
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }

    @Override
    public void postExecute(@NonNull ClientTransactionHandler client,
            @NonNull PendingTransactionActions pendingActions) {
        // TODO(lifecycler): Use interface callback instead of actual implementation.
        ActivityClient.getInstance().activityResumed(getActivityToken(),
                client.isHandleSplashScreenExit(getActivityToken()));
    }

    @Override
    public int getTargetState() {
        return ON_RESUME;
    }

    // Parcelable implementation

    /** Writes to Parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mProcState);
        dest.writeBoolean(mIsForward);
        dest.writeBoolean(mShouldSendCompatFakeFocus);
    }

    /** Reads from Parcel. */
    private ResumeActivityItem(@NonNull Parcel in) {
        super(in);
        mProcState = in.readInt();
        mIsForward = in.readBoolean();
        mShouldSendCompatFakeFocus = in.readBoolean();
    }

    public static final @NonNull Creator<ResumeActivityItem> CREATOR = new Creator<>() {
        public ResumeActivityItem createFromParcel(Parcel in) {
            return new ResumeActivityItem(in);
        }

        public ResumeActivityItem[] newArray(int size) {
            return new ResumeActivityItem[size];
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
        final ResumeActivityItem other = (ResumeActivityItem) o;
        return mProcState == other.mProcState
                && mIsForward == other.mIsForward
                && mShouldSendCompatFakeFocus == other.mShouldSendCompatFakeFocus;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + super.hashCode();
        result = 31 * result + mProcState;
        result = 31 * result + (mIsForward ? 1 : 0);
        result = 31 * result + (mShouldSendCompatFakeFocus ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "ResumeActivityItem{" + super.toString()
                + ",procState=" + mProcState
                + ",isForward=" + mIsForward
                + ",shouldSendCompatFakeFocus=" + mShouldSendCompatFakeFocus + "}";
    }
}
