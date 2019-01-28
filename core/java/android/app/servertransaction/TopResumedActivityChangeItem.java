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
 * Top resumed activity changed callback.
 * @hide
 */
public class TopResumedActivityChangeItem extends ClientTransactionItem {

    private boolean mOnTop;

    @Override
    public void execute(ClientTransactionHandler client, IBinder token,
            PendingTransactionActions pendingActions) {
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "topResumedActivityChangeItem");
        client.handleTopResumedActivityChanged(token, mOnTop, "topResumedActivityChangeItem");
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
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

    public static final Creator<TopResumedActivityChangeItem> CREATOR =
            new Creator<TopResumedActivityChangeItem>() {
                public TopResumedActivityChangeItem createFromParcel(Parcel in) {
                    return new TopResumedActivityChangeItem(in);
                }

                public TopResumedActivityChangeItem[] newArray(int size) {
                    return new TopResumedActivityChangeItem[size];
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
