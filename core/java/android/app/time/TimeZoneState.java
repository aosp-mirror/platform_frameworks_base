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
 * A snapshot of the system's time zone state.
 *
 * <p>{@code id} contains the system's time zone ID setting, e.g. "America/Los_Angeles". This
 * will usually agree with {@code TimeZone.getDefault().getID()} but it can be empty in rare cases.
 *
 * <p>{@code userShouldConfirmId} is {@code true} if the system automatic time zone detection logic
 * suggests that the user be asked to confirm the {@code id} value is correct via {@link
 * TimeManager#confirmTimeZone}. If it is not correct, the value can usually be changed via {@link
 * TimeManager#setManualTimeZone}.
 *
 * @hide
 */
@SystemApi
public final class TimeZoneState implements Parcelable {

    public static final @NonNull Creator<TimeZoneState> CREATOR = new Creator<>() {
        public TimeZoneState createFromParcel(Parcel in) {
            return TimeZoneState.createFromParcel(in);
        }

        public TimeZoneState[] newArray(int size) {
            return new TimeZoneState[size];
        }
    };

    @NonNull private final String mId;
    private final boolean mUserShouldConfirmId;

    /** @hide */
    public TimeZoneState(@NonNull String id, boolean userShouldConfirmId) {
        mId = Objects.requireNonNull(id);
        mUserShouldConfirmId = userShouldConfirmId;
    }

    private static TimeZoneState createFromParcel(Parcel in) {
        String zoneId = in.readString8();
        boolean userShouldConfirmId = in.readBoolean();
        return new TimeZoneState(zoneId, userShouldConfirmId);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString8(mId);
        dest.writeBoolean(mUserShouldConfirmId);
    }

    /** @hide */
    @Nullable
    public static TimeZoneState parseCommandLineArgs(@NonNull ShellCommand cmd) {
        String zoneIdString = null;
        Boolean userShouldConfirmId = null;
        String opt;
        while ((opt = cmd.getNextArg()) != null) {
            switch (opt) {
                case "--zone_id": {
                    zoneIdString  = cmd.getNextArgRequired();
                    break;
                }
                case "--user_should_confirm_id": {
                    userShouldConfirmId  = Boolean.parseBoolean(cmd.getNextArgRequired());
                    break;
                }
                default: {
                    throw new IllegalArgumentException("Unknown option: " + opt);
                }
            }
        }
        if (zoneIdString == null) {
            throw new IllegalArgumentException("No zoneId specified.");
        }
        if (userShouldConfirmId == null) {
            throw new IllegalArgumentException("No userShouldConfirmId specified.");
        }
        return new TimeZoneState(zoneIdString, userShouldConfirmId);
    }

    /** @hide */
    public static void printCommandLineOpts(@NonNull PrintWriter pw) {
        pw.println("TimeZoneState options:");
        pw.println("  --zone_id {<Olson ID>}");
        pw.println("  --user_should_confirm_id {true|false}");
        pw.println();
        pw.println("See " + TimeZoneState.class.getName() + " for more information");
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public String getId() {
        return mId;
    }

    public boolean getUserShouldConfirmId() {
        return mUserShouldConfirmId;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TimeZoneState that = (TimeZoneState) o;
        return Objects.equals(mId, that.mId)
                && mUserShouldConfirmId == that.mUserShouldConfirmId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mUserShouldConfirmId);
    }

    @Override
    public String toString() {
        return "TimeZoneState{"
                + "mZoneId=" + mId
                + ", mUserShouldConfirmId=" + mUserShouldConfirmId
                + '}';
    }
}
