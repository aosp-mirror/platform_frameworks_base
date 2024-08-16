/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.window;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityThread;
import android.graphics.Rect;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Stores the window information about a particular Activity.
 * It contains the info that is not part of {@link android.content.res.Configuration}.
 * @hide
 */
public final class ActivityWindowInfo implements Parcelable {

    private boolean mIsEmbedded;

    @NonNull
    private final Rect mTaskBounds = new Rect();

    @NonNull
    private final Rect mTaskFragmentBounds = new Rect();

    public ActivityWindowInfo() {}

    public ActivityWindowInfo(@NonNull ActivityWindowInfo info) {
        set(info);
    }

    /** Copies fields from {@code info}. */
    public void set(@NonNull ActivityWindowInfo info) {
        set(info.mIsEmbedded, info.mTaskBounds, info.mTaskFragmentBounds);
    }

    /** Sets to the given values. */
    public void set(boolean isEmbedded, @NonNull Rect taskBounds,
            @NonNull Rect taskFragmentBounds) {
        mIsEmbedded = isEmbedded;
        mTaskBounds.set(taskBounds);
        mTaskFragmentBounds.set(taskFragmentBounds);
    }

    /**
     * Whether this activity is embedded, which means it is a TaskFragment that doesn't fill the
     * leaf Task.
     */
    public boolean isEmbedded() {
        return mIsEmbedded;
    }

    /**
     * The bounds of the leaf Task window in display space.
     */
    @NonNull
    public Rect getTaskBounds() {
        return mTaskBounds;
    }

    /**
     * The bounds of the leaf TaskFragment window in display space.
     * This can be referring to the bounds of the same window as {@link #getTaskBounds()} when
     * the activity is not embedded.
     */
    @NonNull
    public Rect getTaskFragmentBounds() {
        return mTaskFragmentBounds;
    }

    private ActivityWindowInfo(@NonNull Parcel in) {
        mIsEmbedded = in.readBoolean();
        mTaskBounds.readFromParcel(in);
        mTaskFragmentBounds.readFromParcel(in);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeBoolean(mIsEmbedded);
        mTaskBounds.writeToParcel(dest, flags);
        mTaskFragmentBounds.writeToParcel(dest, flags);
    }

    @NonNull
    public static final Creator<ActivityWindowInfo> CREATOR =
            new Creator<>() {
                @Override
                public ActivityWindowInfo createFromParcel(@NonNull Parcel in) {
                    return new ActivityWindowInfo(in);
                }

                @Override
                public ActivityWindowInfo[] newArray(int size) {
                    return new ActivityWindowInfo[size];
                }
            };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ActivityWindowInfo other = (ActivityWindowInfo) o;
        return mIsEmbedded == other.mIsEmbedded
                && mTaskBounds.equals(other.mTaskBounds)
                && mTaskFragmentBounds.equals(other.mTaskFragmentBounds);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (mIsEmbedded ? 1 : 0);
        result = 31 * result + mTaskBounds.hashCode();
        result = 31 * result + mTaskFragmentBounds.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "ActivityWindowInfo{isEmbedded=" + mIsEmbedded
                + ", taskBounds=" + mTaskBounds
                + ", taskFragmentBounds=" + mTaskFragmentBounds
                + "}";
    }

    /** Gets the {@link ActivityWindowInfo} of the given activity. */
    @Nullable
    public static ActivityWindowInfo getActivityWindowInfo(@NonNull Activity activity) {
        if (activity.isFinishing()) {
            return null;
        }
        final ActivityThread.ActivityClientRecord record = ActivityThread.currentActivityThread()
                .getActivityClient(activity.getActivityToken());
        return record != null ? record.getActivityWindowInfo() : null;
    }
}
