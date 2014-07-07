/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */

package android.app;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * An immutable description of an alarm clock.
 *
 * @see AlarmManager#setAlarmClock
 * @see AlarmManager#getNextAlarmClock
 */
public class AlarmClockInfo implements Parcelable {

    private final long mTriggerTime;
    private final PendingIntent mShowIntent;

    /**
     * Creates a new alarm clock description.
     *
     * @param triggerTime time at which the underlying alarm is triggered in wall time milliseconds
     *                    since the epoch
     * @param showIntent an intent that can be used to show or edit details of
     *                        the alarm clock.
     */
    public AlarmClockInfo(long triggerTime, PendingIntent showIntent) {
        mTriggerTime = triggerTime;
        mShowIntent = showIntent;
    }

    /**
     * Use the {@link #CREATOR}
     * @hide
     */
    AlarmClockInfo(Parcel in) {
        mTriggerTime = in.readLong();
        mShowIntent = in.readParcelable(PendingIntent.class.getClassLoader());
    }

    /**
     * Returns the time at which the alarm is going to trigger.
     *
     * This value is UTC wall clock time in milliseconds, as returned by
     * {@link System#currentTimeMillis()} for example.
     */
    public long getTriggerTime() {
        return mTriggerTime;
    }

    /**
     * Returns an intent intent that can be used to show or edit details of the alarm clock in
     * the application that scheduled it.
     *
     * <p class="note">Beware that any application can retrieve and send this intent, potentially
     * with additional fields filled in. See
     * {@link PendingIntent#send(android.content.Context, int, android.content.Intent)
     * PendingIntent.send()} and {@link android.content.Intent#fillIn Intent.fillIn()}
     * for details.
     */
    public PendingIntent getShowIntent() {
        return mShowIntent;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeLong(mTriggerTime);
        dest.writeParcelable(mShowIntent, flags);
    }

    public static final Creator<AlarmClockInfo> CREATOR = new Creator<AlarmClockInfo>() {
        @Override
        public AlarmClockInfo createFromParcel(Parcel in) {
            return new AlarmClockInfo(in);
        }

        @Override
        public AlarmClockInfo[] newArray(int size) {
            return new AlarmClockInfo[size];
        }
    };
}
