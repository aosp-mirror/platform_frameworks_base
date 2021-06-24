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

import android.annotation.NonNull;
import android.content.ComponentName;
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

    /**
     * The component name of the initial root activity of this TaskFragment, which will be used
     * to configure the relationships for TaskFragments.
     */
    @NonNull
    private final ComponentName mInitialComponentName;

    @NonNull
    private final WindowContainerToken mToken;

    @NonNull
    private final Configuration mConfiguration = new Configuration();

    /** Whether the TaskFragment contains any child Activity. */
    private final boolean mIsEmpty;

    /** Whether this TaskFragment is visible on the window hierarchy. */
    private final boolean mIsVisible;

    public TaskFragmentInfo(
            @NonNull IBinder fragmentToken, @NonNull ComponentName initialComponentName,
            @NonNull WindowContainerToken token, @NonNull Configuration configuration,
            boolean isEmpty, boolean isVisible) {
        if (fragmentToken == null || initialComponentName == null) {
            throw new IllegalArgumentException("Invalid TaskFragmentInfo.");
        }
        mFragmentToken = fragmentToken;
        mInitialComponentName = initialComponentName;
        mToken = token;
        mConfiguration.setTo(configuration);
        mIsEmpty = isEmpty;
        mIsVisible = isVisible;
    }

    public IBinder getFragmentToken() {
        return mFragmentToken;
    }

    public ComponentName getInitialComponentName() {
        return mInitialComponentName;
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

    private TaskFragmentInfo(Parcel in) {
        mFragmentToken = in.readStrongBinder();
        mInitialComponentName = in.readTypedObject(ComponentName.CREATOR);
        mToken = in.readTypedObject(WindowContainerToken.CREATOR);
        mConfiguration.readFromParcel(in);
        mIsEmpty = in.readBoolean();
        mIsVisible = in.readBoolean();
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeStrongBinder(mFragmentToken);
        dest.writeTypedObject(mInitialComponentName, flags);
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
                + " initialComponentName=" + mInitialComponentName
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
