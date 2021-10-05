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

import static android.app.servertransaction.ActivityLifecycleItem.ON_RESUME;
import static android.app.servertransaction.ActivityLifecycleItem.UNDEFINED;
import static android.os.Trace.TRACE_TAG_ACTIVITY_MANAGER;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.app.ResultInfo;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Trace;

import java.util.List;
import java.util.Objects;

/**
 * Activity result delivery callback.
 * @hide
 */
public class ActivityResultItem extends ActivityTransactionItem {

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private List<ResultInfo> mResultInfoList;

    /**
     * Correct the lifecycle of activity result after {@link android.os.Build.VERSION_CODES#S} to
     * guarantee that an activity gets activity result just before resume.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.S)
    public static final long CALL_ACTIVITY_RESULT_BEFORE_RESUME = 78294732L;

    @Override
    public int getPostExecutionState() {
        return CompatChanges.isChangeEnabled(CALL_ACTIVITY_RESULT_BEFORE_RESUME)
                ? ON_RESUME : UNDEFINED;
    }

    @Override
    public void execute(ClientTransactionHandler client, ActivityClientRecord r,
            PendingTransactionActions pendingActions) {
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "activityDeliverResult");
        client.handleSendResult(r, mResultInfoList, "ACTIVITY_RESULT");
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }


    // ObjectPoolItem implementation

    private ActivityResultItem() {}

    /** Obtain an instance initialized with provided params. */
    public static ActivityResultItem obtain(List<ResultInfo> resultInfoList) {
        ActivityResultItem instance = ObjectPool.obtain(ActivityResultItem.class);
        if (instance == null) {
            instance = new ActivityResultItem();
        }
        instance.mResultInfoList = resultInfoList;

        return instance;
    }

    @Override
    public void recycle() {
        mResultInfoList = null;
        ObjectPool.recycle(this);
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

    public static final @NonNull Parcelable.Creator<ActivityResultItem> CREATOR =
            new Parcelable.Creator<ActivityResultItem>() {
        public ActivityResultItem createFromParcel(Parcel in) {
            return new ActivityResultItem(in);
        }

        public ActivityResultItem[] newArray(int size) {
            return new ActivityResultItem[size];
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
        final ActivityResultItem other = (ActivityResultItem) o;
        return Objects.equals(mResultInfoList, other.mResultInfoList);
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
