/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.app.assist;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.service.contentcapture.ContentCaptureService;
import android.view.contentcapture.ContentCaptureContext;
import android.view.translation.UiTranslationManager;

import com.android.internal.annotations.Immutable;

/**
 * The class is used to identify an instance of an Activity. The system provides this to services
 * that need to request operations on a specific Activity. For example, the system provides this in
 * {@link ContentCaptureContext} to {@link ContentCaptureService} which can use it to issue requests
 * like {@link UiTranslationManager#startTranslation}.
 *
 * @hide
 */
@Immutable
@SystemApi
@TestApi
public final class ActivityId implements Parcelable {

    /**
     * The identifier of the task this activity is in.
     */
    private final int mTaskId;
    /**
     * The identifier of the activity.
     */
    @Nullable
    private final IBinder mActivityId;

    /**
     * @hide
     */
    @TestApi
    public ActivityId(int taskId, @Nullable IBinder activityId) {
        mTaskId = taskId;
        mActivityId = activityId;
    }

    /**
     * @hide
     */
    public ActivityId(@NonNull Parcel source) {
        mTaskId = source.readInt();
        mActivityId = source.readStrongBinder();
    }

    /**
     * The identifier of the task this activity is in.
     * @hide
     */
    @TestApi
    public int getTaskId() {
        return mTaskId;
    }

    /**
     * The identifier of the activity. In some case, this value may be null, e.g. the child session
     * of content capture.
     * @hide
     */
    @Nullable
    @TestApi
    public IBinder getToken() {
        return mActivityId;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mTaskId);
        dest.writeStrongBinder(mActivityId);
    }

    /**
     * Creates {@link ActivityId} instances from parcels.
     */
    @NonNull
    public static final Parcelable.Creator<ActivityId> CREATOR =
            new Parcelable.Creator<ActivityId>() {
                @Override
                public ActivityId createFromParcel(Parcel parcel) {
                    return new ActivityId(parcel);
                }

                @Override
                public ActivityId[] newArray(int size) {
                    return new ActivityId[size];
                }
            };

    @Override
    public String toString() {
        return "ActivityId { taskId = " + mTaskId + ", activityId = " + mActivityId + " }";
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ActivityId that = (ActivityId) o;
        if (mTaskId != that.mTaskId) {
            return false;
        }
        return mActivityId != null
                ? mActivityId.equals(that.mActivityId)
                : that.mActivityId == null;
    }

    @Override
    public int hashCode() {
        int result = mTaskId;
        result = 31 * result + (mActivityId != null ? mActivityId.hashCode() : 0);
        return result;
    }
}
