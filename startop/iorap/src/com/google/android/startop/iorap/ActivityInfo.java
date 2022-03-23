/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.google.android.startop.iorap;

import java.util.Objects;

import android.os.Parcelable;
import android.os.Parcel;

/**
 * Provide minimal information for launched activities to iorap.<br /><br />
 *
 * This uniquely identifies a system-wide activity by providing the {@link #packageName} and
 * {@link #activityName}.
 *
 * @see ActivityHintEvent
 * @see AppIntentEvent
 *
 * @hide
 */
public class ActivityInfo implements Parcelable {

    /** The name of the package, for example {@code com.android.calculator}. */
    public final String packageName;
    /** The name of the activity, for example {@code .activities.activity.MainActivity} */
    public final String activityName;

    public ActivityInfo(String packageName, String activityName) {
        this.packageName = packageName;
        this.activityName = activityName;

        checkConstructorArguments();
    }

    private void checkConstructorArguments() {
        Objects.requireNonNull(packageName, "packageName");
        Objects.requireNonNull(activityName, "activityName");
    }

    @Override
    public String toString() {
        return String.format("{packageName: %s, activityName: %s}", packageName, activityName);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        } else if (other instanceof ActivityInfo) {
            return equals((ActivityInfo) other);
        }
        return false;
    }

    private boolean equals(ActivityInfo other) {
        return Objects.equals(packageName, other.packageName) &&
                Objects.equals(activityName, other.activityName);
    }

    //<editor-fold desc="Binder boilerplate">
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(packageName);
        out.writeString(activityName);
    }

    private ActivityInfo(Parcel in) {
        packageName = in.readString();
        activityName = in.readString();

        checkConstructorArguments();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Parcelable.Creator<ActivityInfo> CREATOR
            = new Parcelable.Creator<ActivityInfo>() {
        public ActivityInfo createFromParcel(Parcel in) {
            return new ActivityInfo(in);
        }

        public ActivityInfo[] newArray(int size) {
            return new ActivityInfo[size];
        }
    };
    //</editor-fold>
}
