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

/**
 * @hide
 */
public class LauncherActivityInfoInternal implements Parcelable {
    @UnsupportedAppUsage
    @NonNull private ActivityInfo mActivityInfo;
    @NonNull private ComponentName mComponentName;
    @NonNull private IncrementalStatesInfo mIncrementalStatesInfo;

    /**
     * @param info ActivityInfo from which to create the LauncherActivityInfo.
     * @param incrementalStatesInfo The package's states.
     */
    public LauncherActivityInfoInternal(@NonNull ActivityInfo info,
            @NonNull IncrementalStatesInfo incrementalStatesInfo) {
        mActivityInfo = info;
        mComponentName = new ComponentName(info.packageName, info.name);
        mIncrementalStatesInfo = incrementalStatesInfo;
    }

    public LauncherActivityInfoInternal(Parcel source) {
        mActivityInfo = source.readParcelable(ActivityInfo.class.getClassLoader(), android.content.pm.ActivityInfo.class);
        mComponentName = new ComponentName(mActivityInfo.packageName, mActivityInfo.name);
        mIncrementalStatesInfo = source.readParcelable(
                IncrementalStatesInfo.class.getClassLoader(), android.content.pm.IncrementalStatesInfo.class);
    }

    public ComponentName getComponentName() {
        return mComponentName;
    }

    public ActivityInfo getActivityInfo() {
        return mActivityInfo;
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
        dest.writeParcelable(mActivityInfo, 0);
        dest.writeParcelable(mIncrementalStatesInfo, 0);
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
