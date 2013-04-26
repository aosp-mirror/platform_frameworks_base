/* //device/java/android/android/app/INotificationManager.aidl
**
** Copyright 2007, The Android Open Source Project
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

import android.app.ITransientNotification;
import android.service.notification.StatusBarNotification;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Intent;
import android.service.notification.INotificationListener;

/** {@hide} */
interface INotificationManager
{
    void cancelAllNotifications(String pkg, int userId);

    void enqueueToast(String pkg, ITransientNotification callback, int duration);
    void cancelToast(String pkg, ITransientNotification callback);
    void enqueueNotificationWithTag(String pkg, String basePkg, String tag, int id,
            in Notification notification, inout int[] idReceived, int userId);
    void cancelNotificationWithTag(String pkg, String tag, int id, int userId);

    void setNotificationsEnabledForPackage(String pkg, int uid, boolean enabled);
    boolean areNotificationsEnabledForPackage(String pkg, int uid);

    StatusBarNotification[] getActiveNotifications(String callingPkg);
    StatusBarNotification[] getHistoricalNotifications(String callingPkg, int count);

    void registerListener(in INotificationListener listener, in ComponentName component, int userid);
    void unregisterListener(in INotificationListener listener, int userid);

    void cancelNotificationFromListener(in INotificationListener token, String pkg, String tag, int id);
    void cancelAllNotificationsFromListener(in INotificationListener token);

    StatusBarNotification[] getActiveNotificationsFromListener(in INotificationListener token);
}