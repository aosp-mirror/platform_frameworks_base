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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.res.Configuration;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Stores information about a particular TaskFragment.
 * @hide
 */
public final class TaskFragmentInfo implements Parcelable {

    /**
     * Client assigned unique token in {@link TaskFragmentCreationParams#fragmentToken} to create
     * this TaskFragment with.
     */
    @NonNull
    private final IBinder mFragmentToken;

    @NonNull
    private final WindowContainerToken mToken;

    @NonNull
    private final Configuration mConfiguration = new Configuration();

    /** Whether the TaskFragment contains any child Activity. */
    private final boolean mIsEmpty;

    /** Whether this TaskFragment is visible on the window hierarchy. */
    private final boolean mIsVisible;

    public TaskFragmentInfo(
            @NonNull IBinder fragmentToken, @NonNull WindowContainerToken token,
            @NonNull Configuration configuration, boolean isEmpty, boolean isVisible) {
        if (fragmentToken == null) {
            throw new IllegalArgumentException("Invalid TaskFragmentInfo.");
        }
        mFragmentToken = fragmentToken;
        mToken = token;
        mConfiguration.setTo(configuration);
        mIsEmpty = isEmpty;
        mIsVisible = isVisible;
    }

    public IBinder getFragmentToken() {
        return mFragmentToken;
    }

    public WindowContainerToken getToken() {
        return mToken;
    }

    public Configuration getConfiguration() {
        return mConfiguration;
    }

    public boolean isEmpty() {
        return mIsEmpty;
    }

    public boolean isVisible() {
        return mIsVisible;
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
                && mIsVisible == that.mIsVisible
                && getWindowingMode() == that.getWindowingMode();
    }

    private TaskFragmentInfo(Parcel in) {
        mFragmentToken = in.readStrongBinder();
        mToken = in.readTypedObject(WindowContainerToken.CREATOR);
        mConfiguration.readFromParcel(in);
        mIsEmpty = in.readBoolean();
        mIsVisible = in.readBoolean();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mFragmentToken);
        dest.writeTypedObject(mToken, flags);
        mConfiguration.writeToParcel(dest, flags);
        dest.writeBoolean(mIsEmpty);
        dest.writeBoolean(mIsVisible);
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
                + " isVisible=" + mIsVisible
                + "}";
    }

    @Override
    public int describeContents() {
        return 0;
    }
}
