/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.content.pm;

import android.annotation.NonNull;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.UserHandle;

/**
 * @hide
 */
public class LauncherActivityInfoInternal implements Parcelable {
    @UnsupportedAppUsage
    @NonNull private ActivityInfo mActivityInfo;
    @NonNull private ComponentName mComponentName;
    @NonNull private IncrementalStatesInfo mIncrementalStatesInfo;
    @NonNull private UserHandle mUser;

    /**
     * @param info ActivityInfo from which to create the LauncherActivityInfo.
     * @param incrementalStatesInfo The package's states.
     * @param user The user the activity info belongs to.
     */
    public LauncherActivityInfoInternal(@NonNull ActivityInfo info,
            @NonNull IncrementalStatesInfo incrementalStatesInfo,
            @NonNull UserHandle user) {
        mActivityInfo = info;
        mComponentName = new ComponentName(info.packageName, info.name);
        mIncrementalStatesInfo = incrementalStatesInfo;
        mUser = user;
    }

    public LauncherActivityInfoInternal(Parcel source) {
        mActivityInfo = source.readTypedObject(ActivityInfo.CREATOR);
        mComponentName = new ComponentName(mActivityInfo.packageName, mActivityInfo.name);
        mIncrementalStatesInfo = source.readTypedObject(IncrementalStatesInfo.CREATOR);
        mUser = source.readTypedObject(UserHandle.CREATOR);
    }

    public ComponentName getComponentName() {
        return mComponentName;
    }

    public ActivityInfo getActivityInfo() {
        return mActivityInfo;
    }

    public UserHandle getUser() {
        return mUser;
    }

    public IncrementalStatesInfo getIncrementalStatesInfo() {
        return mIncrementalStatesInfo;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeTypedObject(mActivityInfo, flags);
        dest.writeTypedObject(mIncrementalStatesInfo, flags);
        dest.writeTypedObject(mUser, flags);
    }

    public static final @android.annotation.NonNull Creator<LauncherActivityInfoInternal> CREATOR =
            new Creator<LauncherActivityInfoInternal>() {
        public LauncherActivityInfoInternal createFromParcel(Parcel source) {
            return new LauncherActivityInfoInternal(source);
        }
        public LauncherActivityInfoInternal[] newArray(int size) {
            return new LauncherActivityInfoInternal[size];
        }
    };
}
