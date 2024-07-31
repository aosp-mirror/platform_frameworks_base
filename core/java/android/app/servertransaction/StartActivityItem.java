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
import android.app.ActivityOptions.SceneTransitionInfo;
import android.app.ActivityThread.ActivityClientRecord;
import android.app.ClientTransactionHandler;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Trace;

/**
 * Request to move an activity to started and visible state.
 *
 * @hide
 */
public class StartActivityItem extends ActivityLifecycleItem {

    @Nullable
    private final SceneTransitionInfo mSceneTransitionInfo;

    public StartActivityItem(@NonNull IBinder activityToken,
            @Nullable SceneTransitionInfo sceneTransitionInfo) {
        super(activityToken);
        mSceneTransitionInfo = sceneTransitionInfo;
    }

    @Override
    public void execute(@NonNull ClientTransactionHandler client, @NonNull ActivityClientRecord r,
            @NonNull PendingTransactionActions pendingActions) {
        Trace.traceBegin(TRACE_TAG_ACTIVITY_MANAGER, "startActivityItem");
        client.handleStartActivity(r, pendingActions, mSceneTransitionInfo);
        Trace.traceEnd(TRACE_TAG_ACTIVITY_MANAGER);
    }

    @Override
    public int getTargetState() {
        return ON_START;
    }

    // Parcelable implementation

    /** Writes to Parcel. */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeTypedObject(mSceneTransitionInfo, flags);
    }

    /** Reads from Parcel. */
    private StartActivityItem(@NonNull Parcel in) {
        super(in);
        mSceneTransitionInfo = in.readTypedObject(SceneTransitionInfo.CREATOR);
    }

    public static final @NonNull Creator<StartActivityItem> CREATOR = new Creator<>() {
        public StartActivityItem createFromParcel(@NonNull Parcel in) {
            return new StartActivityItem(in);
        }

        public StartActivityItem[] newArray(int size) {
            return new StartActivityItem[size];
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
        final StartActivityItem other = (StartActivityItem) o;
        return (mSceneTransitionInfo == null) == (other.mSceneTransitionInfo == null);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + super.hashCode();
        result = 31 * result + (mSceneTransitionInfo != null ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        return "StartActivityItem{" + super.toString()
                + ",sceneTransitionInfo=" + mSceneTransitionInfo + "}";
    }
}

