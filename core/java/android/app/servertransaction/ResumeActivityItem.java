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
 * Request to move an activity to resumed state.
 * @hide
 */
public class ResumeActivityItem extends ActivityLifecycleItem {

    private static final String TAG = "ResumeActivityItem";

    private final int mProcState;
    private final boolean mIsForward;

    private int mLifecycleSeq;

    public ResumeActivityItem(int procState, boolean isForward) {
        mProcState = procState;
        mIsForward = isForward;
    }

    @Override
    public void prepare(ClientTransactionHandler client, IBinder token) {
        mLifecycleSeq = client.getLifecycleSeq();
        if (DEBUG_ORDER) {
            Slog.d(TAG, "Resume transaction for " + client + " received seq: "
                    + mLifecycleSeq);
        }
        client.updateProcessState(mProcState, false);
    }

    @Override
    public void execute(ClientTransactionHandler client, IBinder token) {
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "activityResume");
        client.handleResumeActivity(token, true /* clearHide */, mIsForward,
                true /* reallyResume */, mLifecycleSeq, "RESUME_ACTIVITY");
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }

    @Override
    public int getTargetState() {
        return RESUMED;
    }


    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mProcState);
        dest.writeBoolean(mIsForward);
    }

    /** Read from Parcel. */
    private ResumeActivityItem(Parcel in) {
        mProcState = in.readInt();
        mIsForward = in.readBoolean();
    }

    public static final Creator<ResumeActivityItem> CREATOR =
            new Creator<ResumeActivityItem>() {
        public ResumeActivityItem createFromParcel(Parcel in) {
            return new ResumeActivityItem(in);
        }

        public ResumeActivityItem[] newArray(int size) {
            return new ResumeActivityItem[size];
        }
    };
}
