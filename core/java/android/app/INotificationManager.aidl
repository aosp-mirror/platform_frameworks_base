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
import android.app.Notification;
import android.content.Intent;

/** {@hide} */
interface INotificationManager
{
    /** @deprecated use {@link #enqueueNotificationWithTag} instead */
    void enqueueNotification(String pkg, int id, in Notification notification, inout int[] idReceived);
    /** @deprecated use {@link #cancelNotificationWithTag} instead */
    void cancelNotification(String pkg, int id);
    void cancelAllNotifications(String pkg);

    void enqueueToast(String pkg, ITransientNotification callback, int duration);
    void cancelToast(String pkg, ITransientNotification callback);
    void enqueueNotificationWithTag(String pkg, String tag, int id, in Notification notification, inout int[] idReceived);
    void cancelNotificationWithTag(String pkg, String tag, int id);

    void setNotificationsEnabledForPackage(String pkg, boolean enabled);
    boolean areNotificationsEnabledForPackage(String pkg);
}

