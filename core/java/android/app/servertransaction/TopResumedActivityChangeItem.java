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
import android.app.ActivityClient;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Trace;

/**
 * Top resumed activity changed callback.
 * @hide
 */
public class TopResumedActivityChangeItem extends ActivityTransactionItem {

    private boolean mOnTop;

    @Override
    public void execute(ClientTransactionHandler client, ActivityClientRecord r,
            PendingTransactionActions pendingActions) {
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "topResumedActivityChangeItem");
        client.handleTopResumedActivityChanged(r, mOnTop, "topResumedActivityChangeItem");
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }

    @Override
    public void postExecute(ClientTransactionHandler client, IBinder token,
            PendingTransactionActions pendingActions) {
        if (mOnTop) {
            return;
        }

        // The loss of top resumed state can always be reported immediately in postExecute
        // because only three cases are possible:
        // 1. Activity is in RESUMED state now and it just handled the callback in #execute().
        // 2. Activity wasn't RESUMED yet, which means that it didn't receive the top state yet.
        // 3. Activity is PAUSED or in other lifecycle state after PAUSED. In this case top resumed
        // state loss was already called right before pausing.
        ActivityClient.getInstance().activityTopResumedStateLost();
    }


    // ObjectPoolItem implementation

    private TopResumedActivityChangeItem() {}

    /** Obtain an instance initialized with provided params. */
    public static TopResumedActivityChangeItem obtain(boolean onTop) {
        TopResumedActivityChangeItem instance =
                ObjectPool.obtain(TopResumedActivityChangeItem.class);
        if (instance == null) {
            instance = new TopResumedActivityChangeItem();
        }
        instance.mOnTop = onTop;

        return instance;
    }

    @Override
    public void recycle() {
        mOnTop = false;
        ObjectPool.recycle(this);
    }


    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeBoolean(mOnTop);
    }

    /** Read from Parcel. */
    private TopResumedActivityChangeItem(Parcel in) {
        mOnTop = in.readBoolean();
    }

    public static final @NonNull Creator<TopResumedActivityChangeItem> CREATOR =
            new Creator<TopResumedActivityChangeItem>() {
        public TopResumedActivityChangeItem createFromParcel(Parcel in) {
            return new TopResumedActivityChangeItem(in);
        }

        public TopResumedActivityChangeItem[] newArray(int size) {
            return new TopResumedActivityChangeItem[size];
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
        final TopResumedActivityChangeItem other = (TopResumedActivityChangeItem) o;
        return mOnTop == other.mOnTop;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (mOnTop ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "TopResumedActivityChangeItem{onTop=" + mOnTop + "}";
    }
}
