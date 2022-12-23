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
import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Trace;

/**
 * Request to move an activity to resumed state.
 * @hide
 */
public class ResumeActivityItem extends ActivityLifecycleItem {

    private static final String TAG = "ResumeActivityItem";

    private int mProcState;
    private boolean mUpdateProcState;
    private boolean mIsForward;
    // Whether we should send compat fake focus when the activity is resumed. This is needed
    // because some game engines wait to get focus before drawing the content of the app.
    private boolean mShouldSendCompatFakeFocus;

    @Override
    public void preExecute(ClientTransactionHandler client, IBinder token) {
        if (mUpdateProcState) {
            client.updateProcessState(mProcState, false);
        }
    }

    @Override
    public void execute(ClientTransactionHandler client, ActivityClientRecord r,
            PendingTransactionActions pendingActions) {
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "activityResume");
        client.handleResumeActivity(r, true /* finalStateRequest */, mIsForward,
                mShouldSendCompatFakeFocus, "RESUME_ACTIVITY");
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }

    @Override
    public void postExecute(ClientTransactionHandler client, IBinder token,
            PendingTransactionActions pendingActions) {
        // TODO(lifecycler): Use interface callback instead of actual implementation.
        ActivityClient.getInstance().activityResumed(token, client.isHandleSplashScreenExit(token));
    }

    @Override
    public int getTargetState() {
        return ON_RESUME;
    }


    // ObjectPoolItem implementation

    private ResumeActivityItem() {}

    /** Obtain an instance initialized with provided params. */
    public static ResumeActivityItem obtain(int procState, boolean isForward,
            boolean shouldSendCompatFakeFocus) {
        ResumeActivityItem instance = ObjectPool.obtain(ResumeActivityItem.class);
        if (instance == null) {
            instance = new ResumeActivityItem();
        }
        instance.mProcState = procState;
        instance.mUpdateProcState = true;
        instance.mIsForward = isForward;
        instance.mShouldSendCompatFakeFocus = shouldSendCompatFakeFocus;

        return instance;
    }

    /** Obtain an instance initialized with provided params. */
    public static ResumeActivityItem obtain(boolean isForward, boolean shouldSendCompatFakeFocus) {
        ResumeActivityItem instance = ObjectPool.obtain(ResumeActivityItem.class);
        if (instance == null) {
            instance = new ResumeActivityItem();
        }
        instance.mProcState = ActivityManager.PROCESS_STATE_UNKNOWN;
        instance.mUpdateProcState = false;
        instance.mIsForward = isForward;
        instance.mShouldSendCompatFakeFocus = shouldSendCompatFakeFocus;

        return instance;
    }

    @Override
    public void recycle() {
        super.recycle();
        mProcState = ActivityManager.PROCESS_STATE_UNKNOWN;
        mUpdateProcState = false;
        mIsForward = false;
        mShouldSendCompatFakeFocus = false;
        ObjectPool.recycle(this);
    }


    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mProcState);
        dest.writeBoolean(mUpdateProcState);
        dest.writeBoolean(mIsForward);
        dest.writeBoolean(mShouldSendCompatFakeFocus);
    }

    /** Read from Parcel. */
    private ResumeActivityItem(Parcel in) {
        mProcState = in.readInt();
        mUpdateProcState = in.readBoolean();
        mIsForward = in.readBoolean();
        mShouldSendCompatFakeFocus = in.readBoolean();
    }

    public static final @NonNull Creator<ResumeActivityItem> CREATOR =
            new Creator<ResumeActivityItem>() {
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
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ResumeActivityItem other = (ResumeActivityItem) o;
        return mProcState == other.mProcState && mUpdateProcState == other.mUpdateProcState
                && mIsForward == other.mIsForward
                && mShouldSendCompatFakeFocus == other.mShouldSendCompatFakeFocus;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + mProcState;
        result = 31 * result + (mUpdateProcState ? 1 : 0);
        result = 31 * result + (mIsForward ? 1 : 0);
        result = 31 * result + (mShouldSendCompatFakeFocus ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "ResumeActivityItem{procState=" + mProcState
                + ",updateProcState=" + mUpdateProcState + ",isForward=" + mIsForward
                + ",shouldSendCompatFakeFocus=" + mShouldSendCompatFakeFocus + "}";
    }
}
