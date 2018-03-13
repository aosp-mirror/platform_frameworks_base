/*
 * Copyright 2018 The Android Open Source Project
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

package android.media.update;

import android.app.Notification;
import android.content.Intent;
import android.media.MediaSession2;
import android.media.MediaSessionService2.MediaNotification;
import android.os.IBinder;

/**
 * @hide
 */
public interface MediaSessionService2Provider {
    MediaSession2 getSession_impl();
    MediaNotification onUpdateNotification_impl();

    // Service
    void onCreate_impl();
    IBinder onBind_impl(Intent intent);

    interface MediaNotificationProvider {
        int getNotificationId_impl();
        Notification getNotification_impl();
    }
}
