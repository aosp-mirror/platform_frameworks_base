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
import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Trace;

/**
 * Request to destroy an activity.
 * @hide
 */
public class DestroyActivityItem extends ActivityLifecycleItem {

    private boolean mFinished;

    @Override
    public void preExecute(@NonNull ClientTransactionHandler client) {
        client.getActivitiesToBeDestroyed().put(getActivityToken(), this);
    }

    @Override
    public void execute(@NonNull ClientTransactionHandler client, @NonNull ActivityClientRecord r,
            @NonNull PendingTransactionActions pendingActions) {
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "activityDestroy");
        client.handleDestroyActivity(r, mFinished,
                false /* getNonConfigInstance */, "DestroyActivityItem");
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }

    @Override
    public void postExecute(@NonNull ClientTransactionHandler client,
            @NonNull PendingTransactionActions pendingActions) {
        // Cleanup after execution.
        client.getActivitiesToBeDestroyed().remove(getActivityToken());
    }

    @Override
    public int getTargetState() {
        return ON_DESTROY;
    }

    // ObjectPoolItem implementation

    private DestroyActivityItem() {}

    /** Obtain an instance initialized with provided params. */
    @NonNull
    public static DestroyActivityItem obtain(@NonNull IBinder activityToken, boolean finished) {
        DestroyActivityItem instance = ObjectPool.obtain(DestroyActivityItem.class);
        if (instance == null) {
            instance = new DestroyActivityItem();
        }
        instance.setActivityToken(activityToken);
        instance.mFinished = finished;

        return instance;
    }

    @Override
    public void recycle() {
        super.recycle();
        mFinished = false;
        ObjectPool.recycle(this);
    }

    // Parcelable implementation

    /** Write to Parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeBoolean(mFinished);
    }

    /** Read from Parcel. */
    private DestroyActivityItem(@NonNull Parcel in) {
        super(in);
        mFinished = in.readBoolean();
    }

    public static final @NonNull Creator<DestroyActivityItem> CREATOR = new Creator<>() {
        public DestroyActivityItem createFromParcel(@NonNull Parcel in) {
            return new DestroyActivityItem(in);
        }

        public DestroyActivityItem[] newArray(int size) {
            return new DestroyActivityItem[size];
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
        final DestroyActivityItem other = (DestroyActivityItem) o;
        return mFinished == other.mFinished;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + super.hashCode();
        result = 31 * result + (mFinished ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "DestroyActivityItem{" + super.toString()
                + ",finished=" + mFinished + "}";
    }
}
