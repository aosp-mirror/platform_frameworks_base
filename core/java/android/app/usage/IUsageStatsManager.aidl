/**
 * Copyright (c) 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.usage;

import android.app.PendingIntent;
import android.app.usage.UsageEvents;
import android.content.pm.ParceledListSlice;

import java.util.Map;

/**
 * System private API for talking with the UsageStatsManagerService.
 *
 * {@hide}
 */
interface IUsageStatsManager {
    ParceledListSlice queryUsageStats(int bucketType, long beginTime, long endTime,
            String callingPackage);
    ParceledListSlice queryConfigurationStats(int bucketType, long beginTime, long endTime,
            String callingPackage);
    ParceledListSlice queryEventStats(int bucketType, long beginTime, long endTime,
            String callingPackage);
    UsageEvents queryEvents(long beginTime, long endTime, String callingPackage);
    UsageEvents queryEventsForPackage(long beginTime, long endTime, String callingPackage);
    UsageEvents queryEventsForUser(long beginTime, long endTime, int userId, String callingPackage);
    UsageEvents queryEventsForPackageForUser(long beginTime, long endTime, int userId, String pkg, String callingPackage);
    void setAppInactive(String packageName, boolean inactive, int userId);
    boolean isAppInactive(String packageName, int userId);
    void whitelistAppTemporarily(String packageName, long duration, int userId);
    void onCarrierPrivilegedAppsChanged();
    void reportChooserSelection(String packageName, int userId, String contentType,
            in String[] annotations, String action);
    int getAppStandbyBucket(String packageName, String callingPackage, int userId);
    void setAppStandbyBucket(String packageName, int bucket, int userId);
    ParceledListSlice getAppStandbyBuckets(String callingPackage, int userId);
    void setAppStandbyBuckets(in ParceledListSlice appBuckets, int userId);
    void registerAppUsageObserver(int observerId, in String[] packages, long timeLimitMs,
            in PendingIntent callback, String callingPackage);
    void unregisterAppUsageObserver(int observerId, String callingPackage);
    void registerUsageSessionObserver(int sessionObserverId, in String[] observed, long timeLimitMs,
            long sessionThresholdTimeMs, in PendingIntent limitReachedCallbackIntent,
            in PendingIntent sessionEndCallbackIntent, String callingPackage);
    void unregisterUsageSessionObserver(int sessionObserverId, String callingPackage);
    void registerAppUsageLimitObserver(int observerId, in String[] packages, long timeLimitMs,
            in PendingIntent callback, String callingPackage);
    void unregisterAppUsageLimitObserver(int observerId, String callingPackage);
    void reportUsageStart(in IBinder activity, String token, String callingPackage);
    void reportPastUsageStart(in IBinder activity, String token, long timeAgoMs,
            String callingPackage);
    void reportUsageStop(in IBinder activity, String token, String callingPackage);
    int getUsageSource();
    void forceUsageSourceSettingRead();
}
