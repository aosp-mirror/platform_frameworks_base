/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.app;

import android.annotation.IntDef;
import android.content.ComponentName;
import android.os.Parcel;
import android.os.Parcelable;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Information returned after waiting for an activity start.
 *
 * @hide
 */
public class WaitResult implements Parcelable {

    /**
     * The state at which a launch sequence had started.
     *
     * @see <a href="https://developer.android.com/topic/performance/vitals/launch-time"App startup
     * time</a>
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(prefix = {"LAUNCH_STATE_"}, value = {
            LAUNCH_STATE_UNKNOWN,
            LAUNCH_STATE_COLD,
            LAUNCH_STATE_WARM,
            LAUNCH_STATE_HOT,
            LAUNCH_STATE_RELAUNCH
    })
    public @interface LaunchState {
    }

    /**
     * Not considered as a launch event, e.g. the activity is already on top.
     */
    public static final int LAUNCH_STATE_UNKNOWN = 0;

    /**
     * Cold launch sequence: a new process has started.
     */
    public static final int LAUNCH_STATE_COLD = 1;

    /**
     * Warm launch sequence: process reused, but activity has to be created.
     */
    public static final int LAUNCH_STATE_WARM = 2;

    /**
     * Hot launch sequence: process reused, activity brought-to-top.
     */
    public static final int LAUNCH_STATE_HOT = 3;

    /**
     * Relaunch launch sequence: process reused, but activity has to be destroyed and created.
     * E.g. the current device configuration is different from the background activity that will be
     * brought to foreground, and the activity doesn't declare to handle the change.
     */
    public static final int LAUNCH_STATE_RELAUNCH = 4;

    public static final int INVALID_DELAY = -1;
    public int result;
    public boolean timeout;
    public ComponentName who;
    public long totalTime;
    public @LaunchState int launchState;

    public WaitResult() {
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(result);
        dest.writeInt(timeout ? 1 : 0);
        ComponentName.writeToParcel(who, dest);
        dest.writeLong(totalTime);
        dest.writeInt(launchState);
    }

    public static final @android.annotation.NonNull Parcelable.Creator<WaitResult> CREATOR
            = new Parcelable.Creator<WaitResult>() {
        @Override
        public WaitResult createFromParcel(Parcel source) {
            return new WaitResult(source);
        }

        @Override
        public WaitResult[] newArray(int size) {
            return new WaitResult[size];
        }
    };

    private WaitResult(Parcel source) {
        result = source.readInt();
        timeout = source.readInt() != 0;
        who = ComponentName.readFromParcel(source);
        totalTime = source.readLong();
        launchState = source.readInt();
    }

    public void dump(PrintWriter pw, String prefix) {
        pw.println(prefix + "WaitResult:");
        pw.println(prefix + "  result=" + result);
        pw.println(prefix + "  timeout=" + timeout);
        pw.println(prefix + "  who=" + who);
        pw.println(prefix + "  totalTime=" + totalTime);
        pw.println(prefix + "  launchState=" + launchState);
    }

    public static String launchStateToString(@LaunchState int type) {
        switch (type) {
            case LAUNCH_STATE_COLD:
                return "COLD";
            case LAUNCH_STATE_WARM:
                return "WARM";
            case LAUNCH_STATE_HOT:
                return "HOT";
            case LAUNCH_STATE_RELAUNCH:
                return "RELAUNCH";
            default:
                return "UNKNOWN (" + type + ")";
        }
    }
}