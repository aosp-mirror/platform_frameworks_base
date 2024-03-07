/* //device/java/android/android/app/IAlarmManager.aidl
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/
package android.app;

import android.app.AlarmManager;
import android.app.IAlarmListener;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.WorkSource;

/**
 * System private API for talking with the alarm manager service.
 *
 * {@hide}
 */
interface IAlarmManager {
	/** windowLength == 0 means exact; windowLength < 0 means the let the OS decide */
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    void set(String callingPackage, int type, long triggerAtTime, long windowLength,
            long interval, int flags, in PendingIntent operation, in IAlarmListener listener,
            String listenerTag, in WorkSource workSource, in AlarmManager.AlarmClockInfo alarmClock);
    @EnforcePermission("SET_TIME")
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    boolean setTime(long millis);
    @EnforcePermission("SET_TIME_ZONE")
    void setTimeZone(String zone);
    void remove(in PendingIntent operation, in IAlarmListener listener);
    void removeAll(String packageName);
    long getNextWakeFromIdleTime();
    @UnsupportedAppUsage(maxTargetSdk = 30, trackingBug = 170729553)
    AlarmManager.AlarmClockInfo getNextAlarmClock(int userId);
    boolean canScheduleExactAlarms(String packageName);
    boolean hasScheduleExactAlarm(String packageName, int userId);
    @EnforcePermission("DUMP")
    int getConfigVersion();
}
