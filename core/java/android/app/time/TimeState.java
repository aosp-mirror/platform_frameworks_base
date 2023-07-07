/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app.time;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ShellCommand;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * A snapshot of the system time state.
 *
 * <p>{@code unixEpochTime} contains a snapshot of the system clock time and elapsed realtime clock
 * time.
 *
 * <p>{@code userShouldConfirmTime} is {@code true} if the system automatic time detection logic
 * suggests that the user be asked to confirm the {@code unixEpochTime} value is correct via {@link
 * TimeManager#confirmTime}. If it is not correct, the value can usually be changed via {@link
 * TimeManager#setManualTime}.
 *
 * @hide
 */
@SystemApi
public final class TimeState implements Parcelable {

    public static final @NonNull Creator<TimeState> CREATOR = new Creator<>() {
        public TimeState createFromParcel(Parcel in) {
            return TimeState.createFromParcel(in);
        }

        public TimeState[] newArray(int size) {
            return new TimeState[size];
        }
    };

    @NonNull private final UnixEpochTime mUnixEpochTime;
    private final boolean mUserShouldConfirmTime;

    /** @hide */
    public TimeState(@NonNull UnixEpochTime unixEpochTime, boolean userShouldConfirmTime) {
        mUnixEpochTime = Objects.requireNonNull(unixEpochTime);
        mUserShouldConfirmTime = userShouldConfirmTime;
    }

    private static TimeState createFromParcel(Parcel in) {
        UnixEpochTime unixEpochTime = in.readParcelable(null, UnixEpochTime.class);
        boolean userShouldConfirmId = in.readBoolean();
        return new TimeState(unixEpochTime, userShouldConfirmId);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeParcelable(mUnixEpochTime, 0);
        dest.writeBoolean(mUserShouldConfirmTime);
    }

    /** @hide */
    @Nullable
    public static TimeState parseCommandLineArgs(@NonNull ShellCommand cmd) {
        Long elapsedRealtimeMillis = null;
        Long unixEpochTimeMillis = null;
        Boolean userShouldConfirmTime = null;
        String opt;
        while ((opt = cmd.getNextArg()) != null) {
            switch (opt) {
                case "--elapsed_realtime": {
                    elapsedRealtimeMillis = Long.parseLong(cmd.getNextArgRequired());
                    break;
                }
                case "--unix_epoch_time": {
                    unixEpochTimeMillis = Long.parseLong(cmd.getNextArgRequired());
                    break;
                }
                case "--user_should_confirm_time": {
                    userShouldConfirmTime  = Boolean.parseBoolean(cmd.getNextArgRequired());
                    break;
                }
                default: {
                    throw new IllegalArgumentException("Unknown option: " + opt);
                }
            }
        }

        if (elapsedRealtimeMillis == null) {
            throw new IllegalArgumentException("No elapsedRealtimeMillis specified.");
        }
        if (unixEpochTimeMillis == null) {
            throw new IllegalArgumentException("No unixEpochTimeMillis specified.");
        }
        if (userShouldConfirmTime == null) {
            throw new IllegalArgumentException("No userShouldConfirmTime specified.");
        }

        UnixEpochTime unixEpochTime = new UnixEpochTime(elapsedRealtimeMillis, unixEpochTimeMillis);
        return new TimeState(unixEpochTime, userShouldConfirmTime);
    }

    /** @hide */
    public static void printCommandLineOpts(@NonNull PrintWriter pw) {
        pw.println("TimeState options:");
        pw.println("  --elapsed_realtime <elapsed realtime millis>");
        pw.println("  --unix_epoch_time <Unix epoch time millis>");
        pw.println("  --user_should_confirm_time {true|false}");
        pw.println();
        pw.println("See " + TimeState.class.getName() + " for more information");
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public UnixEpochTime getUnixEpochTime() {
        return mUnixEpochTime;
    }

    public boolean getUserShouldConfirmTime() {
        return mUserShouldConfirmTime;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TimeState that = (TimeState) o;
        return Objects.equals(mUnixEpochTime, that.mUnixEpochTime)
                && mUserShouldConfirmTime == that.mUserShouldConfirmTime;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mUnixEpochTime, mUserShouldConfirmTime);
    }

    @Override
    public String toString() {
        return "TimeState{"
                + "mUnixEpochTime=" + mUnixEpochTime
                + ", mUserShouldConfirmTime=" + mUserShouldConfirmTime
                + '}';
    }
}
