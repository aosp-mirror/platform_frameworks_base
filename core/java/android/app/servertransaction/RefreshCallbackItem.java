/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static android.app.servertransaction.ActivityLifecycleItem.LifecycleState;
import static android.app.servertransaction.ActivityLifecycleItem.ON_PAUSE;
import static android.app.servertransaction.ActivityLifecycleItem.ON_STOP;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.os.IBinder;
import android.os.Parcel;

/**
 * Callback that allows to {@link TransactionExecutor#cycleToPath} to {@link ON_PAUSE} or
 * {@link ON_STOP} in {@link TransactionExecutor#executeTransactionItems} for activity "refresh"
 * flow that goes through "paused -> resumed" or "stopped -> resumed" cycle.
 *
 * <p>This is used in combination with {@link com.android.server.wm.DisplayRotationCompatPolicy}
 * for camera compatibility treatment that handles orientation mismatch between camera buffers and
 * an app window. This allows to clear cached values in apps (e.g. display or camera rotation) that
 * influence camera preview and can lead to sideways or stretching issues.
 *
 * @hide
 */
public class RefreshCallbackItem extends ActivityTransactionItem {

    // Whether refresh should happen using the "stopped -> resumed" cycle or
    // "paused -> resumed" cycle.
    @LifecycleState
    private int mPostExecutionState;

    @Override
    public void execute(@NonNull ClientTransactionHandler client,
            @NonNull ActivityClientRecord r, @NonNull PendingTransactionActions pendingActions) {}

    @Override
    public void postExecute(@NonNull ClientTransactionHandler client,
            @NonNull PendingTransactionActions pendingActions) {
        final ActivityClientRecord r = getActivityClientRecord(client);
        client.reportRefresh(r);
    }

    @Override
    public int getPostExecutionState() {
        return mPostExecutionState;
    }

    @Override
    boolean shouldHaveDefinedPreExecutionState() {
        return false;
    }

    // ObjectPoolItem implementation

    @Override
    public void recycle() {
        super.recycle();
        ObjectPool.recycle(this);
    }

    /**
    * Obtain an instance initialized with provided params.
    * @param postExecutionState indicating whether refresh should happen using the
    *        "stopped -> resumed" cycle or "paused -> resumed" cycle.
    */
    @NonNull
    public static RefreshCallbackItem obtain(@NonNull IBinder activityToken,
            @LifecycleState int postExecutionState) {
        if (postExecutionState != ON_STOP && postExecutionState != ON_PAUSE) {
            throw new IllegalArgumentException(
                    "Only ON_STOP or ON_PAUSE are allowed as a post execution state for "
                            + "RefreshCallbackItem but got " + postExecutionState);
        }
        RefreshCallbackItem instance =
                ObjectPool.obtain(RefreshCallbackItem.class);
        if (instance == null) {
            instance = new RefreshCallbackItem();
        }
        instance.setActivityToken(activityToken);
        instance.mPostExecutionState = postExecutionState;
        return instance;
    }

    private RefreshCallbackItem() {}

    // Parcelable implementation

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeInt(mPostExecutionState);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!super.equals(o)) {
            return false;
        }
        final RefreshCallbackItem other = (RefreshCallbackItem) o;
        return mPostExecutionState == other.mPostExecutionState;
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + super.hashCode();
        result = 31 * result + mPostExecutionState;
        return result;
    }

    @Override
    public String toString() {
        return "RefreshCallbackItem{" + super.toString()
                + ",mPostExecutionState=" + mPostExecutionState + "}";
    }

    private RefreshCallbackItem(@NonNull Parcel in) {
        super(in);
        mPostExecutionState = in.readInt();
    }

    public static final @NonNull Creator<RefreshCallbackItem> CREATOR = new Creator<>() {

        public RefreshCallbackItem createFromParcel(@NonNull Parcel in) {
            return new RefreshCallbackItem(in);
        }

        public RefreshCallbackItem[] newArray(int size) {
            return new RefreshCallbackItem[size];
        }
    };
}
