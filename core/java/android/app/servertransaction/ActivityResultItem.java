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

import static android.app.servertransaction.ActivityLifecycleItem.ON_PAUSE;
import static android.os.Trace.TRACE_TAG_ACTIVITY_MANAGER;

import android.app.ClientTransactionHandler;
import android.app.ResultInfo;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Trace;

import java.util.List;

/**
 * Activity result delivery callback.
 * @hide
 */
public class ActivityResultItem extends ClientTransactionItem {

    private final List<ResultInfo> mResultInfoList;

    public ActivityResultItem(List<ResultInfo> resultInfos) {
        mResultInfoList = resultInfos;
    }

    @Override
    public int getPreExecutionState() {
        return ON_PAUSE;
    }

    @Override
    public void execute(ClientTransactionHandler client, IBinder token,
            PendingTransactionActions pendingActions) {
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "activityDeliverResult");
        client.handleSendResult(token, mResultInfoList);
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }


    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedList(mResultInfoList, flags);
    }

    /** Read from Parcel. */
    private ActivityResultItem(Parcel in) {
        mResultInfoList = in.createTypedArrayList(ResultInfo.CREATOR);
    }

    public static final Parcelable.Creator<ActivityResultItem> CREATOR =
            new Parcelable.Creator<ActivityResultItem>() {
        public ActivityResultItem createFromParcel(Parcel in) {
            return new ActivityResultItem(in);
        }

        public ActivityResultItem[] newArray(int size) {
            return new ActivityResultItem[size];
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
        final ActivityResultItem other = (ActivityResultItem) o;
        return mResultInfoList.equals(other.mResultInfoList);
    }

    @Override
    public int hashCode() {
        return mResultInfoList.hashCode();
    }

    @Override
    public String toString() {
        return "ActivityResultItem{resultInfoList=" + mResultInfoList + "}";
    }
}
