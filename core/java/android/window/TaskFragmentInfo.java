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

package android.window;

import static android.app.WindowConfiguration.WindowingMode;

import static java.util.Objects.requireNonNull;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.res.Configuration;
import android.graphics.Point;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

import java.util.ArrayList;
import java.util.List;

/**
 * Stores information about a particular TaskFragment.
 * @hide
 */
@TestApi
public final class TaskFragmentInfo implements Parcelable {

    /**
     * Client assigned unique token in {@link TaskFragmentCreationParams#getFragmentToken()} to
     * create this TaskFragment with.
     */
    @NonNull
    private final IBinder mFragmentToken;

    @NonNull
    private final WindowContainerToken mToken;

    @NonNull
    private final Configuration mConfiguration = new Configuration();

    /** Whether the TaskFragment contains any child Window Container. */
    private final boolean mIsEmpty;

    /** The number of the running activities in the TaskFragment. */
    private final int mRunningActivityCount;

    /** Whether this TaskFragment is visible on the window hierarchy. */
    private final boolean mIsVisible;

    /**
     * List of Activity tokens that are children of this TaskFragment. It only contains Activities
     * that belong to the organizer process for security.
     */
    @NonNull
    private final List<IBinder> mActivities = new ArrayList<>();

    /** Relative position of the fragment's top left corner in the parent container. */
    private final Point mPositionInParent;

    /** @hide */
    public TaskFragmentInfo(
            @NonNull IBinder fragmentToken, @NonNull WindowContainerToken token,
            @NonNull Configuration configuration, boolean isEmpty, int runningActivityCount,
            boolean isVisible, @NonNull List<IBinder> activities, @NonNull Point positionInParent) {
        mFragmentToken = requireNonNull(fragmentToken);
        mToken = requireNonNull(token);
        mConfiguration.setTo(configuration);
        mIsEmpty = isEmpty;
        mRunningActivityCount = runningActivityCount;
        mIsVisible = isVisible;
        mActivities.addAll(activities);
        mPositionInParent = requireNonNull(positionInParent);
    }

    @NonNull
    public IBinder getFragmentToken() {
        return mFragmentToken;
    }

    @NonNull
    public WindowContainerToken getToken() {
        return mToken;
    }

    @NonNull
    public Configuration getConfiguration() {
        return mConfiguration;
    }

    public boolean isEmpty() {
        return mIsEmpty;
    }

    public boolean hasRunningActivity() {
        return mRunningActivityCount > 0;
    }

    public int getRunningActivityCount() {
        return mRunningActivityCount;
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    @NonNull
    public List<IBinder> getActivities() {
        return mActivities;
    }

    /** Returns the relative position of the fragment's top left corner in the parent container. */
    @NonNull
    public Point getPositionInParent() {
        return mPositionInParent;
    }

    @WindowingMode
    public int getWindowingMode() {
        return mConfiguration.windowConfiguration.getWindowingMode();
    }

    /**
     * Returns {@code true} if the parameters that are important for task fragment organizers are
     * equal between this {@link TaskFragmentInfo} and {@param that}.
     */
    public boolean equalsForTaskFragmentOrganizer(@Nullable TaskFragmentInfo that) {
        if (that == null) {
            return false;
        }

        return mFragmentToken.equals(that.mFragmentToken)
                && mToken.equals(that.mToken)
                && mIsEmpty == that.mIsEmpty
                && mRunningActivityCount == that.mRunningActivityCount
                && mIsVisible == that.mIsVisible
                && getWindowingMode() == that.getWindowingMode()
                && mActivities.equals(that.mActivities)
                && mPositionInParent.equals(that.mPositionInParent);
    }

    private TaskFragmentInfo(Parcel in) {
        mFragmentToken = in.readStrongBinder();
        mToken = in.readTypedObject(WindowContainerToken.CREATOR);
        mConfiguration.readFromParcel(in);
        mIsEmpty = in.readBoolean();
        mRunningActivityCount = in.readInt();
        mIsVisible = in.readBoolean();
        in.readBinderList(mActivities);
        mPositionInParent = requireNonNull(in.readTypedObject(Point.CREATOR));
    }

    /** @hide */
    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mFragmentToken);
        dest.writeTypedObject(mToken, flags);
        mConfiguration.writeToParcel(dest, flags);
        dest.writeBoolean(mIsEmpty);
        dest.writeInt(mRunningActivityCount);
        dest.writeBoolean(mIsVisible);
        dest.writeBinderList(mActivities);
        dest.writeTypedObject(mPositionInParent, flags);
    }

    @NonNull
    public static final Creator<TaskFragmentInfo> CREATOR =
            new Creator<TaskFragmentInfo>() {
                @Override
                public TaskFragmentInfo createFromParcel(Parcel in) {
                    return new TaskFragmentInfo(in);
                }

                @Override
                public TaskFragmentInfo[] newArray(int size) {
                    return new TaskFragmentInfo[size];
                }
            };

    @Override
    public String toString() {
        return "TaskFragmentInfo{"
                + " fragmentToken=" + mFragmentToken
                + " token=" + mToken
                + " isEmpty=" + mIsEmpty
                + " runningActivityCount=" + mRunningActivityCount
                + " isVisible=" + mIsVisible
                + " positionInParent=" + mPositionInParent
                + "}";
    }

    /** @hide */
    @Override
    public int describeContents() {
        return 0;
    }
}
