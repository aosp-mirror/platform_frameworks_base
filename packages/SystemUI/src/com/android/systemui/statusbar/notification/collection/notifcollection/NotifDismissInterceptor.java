/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.notifcollection;

import com.android.systemui.statusbar.notification.collection.NotifCollection;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

/**
 * A way for coordinators to temporarily intercept a user-dismissed notification before a message
 * is sent to system server to officially remove this notification.
 * See {@link NotifCollection#addNotificationDismissInterceptor(NotifDismissInterceptor)}.
 */
public interface NotifDismissInterceptor {
    /** Name to associate with this interceptor (for the purposes of debugging) */
    String getName();

    /**
     * Called on the interceptor immediately after it has been registered. The interceptor should
     * hang on to this callback and execute it whenever it no longer needs to intercept the
     * dismissal of the notification.
     */
    void setCallback(OnEndDismissInterception callback);

    /**
     * Called by the NotifCollection whenever a notification has been dismissed (by the user).
     * If the interceptor returns true, it is considered to be intercepting the notification.
     * Intercepted notifications will not be sent to system server for removal until it is no
     * longer being intercepted. However, the notification can still be cancelled by the app.
     * This method is called on all interceptors even if earlier ones return true.
     */
    boolean shouldInterceptDismissal(NotificationEntry entry);


    /**
     * Called by the NotifCollection to inform a DismissInterceptor that its interception of a notif
     * is no longer valid (usually because the notif has been removed by means other than the
     * user dismissing the notification from the shade, or the notification has been updated). The
     * interceptor should clean up any references it has to the notif in question.
     */
    void cancelDismissInterception(NotificationEntry entry);

    /**
     * Callback for notifying the NotifCollection that it no longer is intercepting the dismissal.
     * If the end of this dismiss interception triggers a dismiss (ie: no other
     * NotifDismissInterceptors are intercepting the entry), NotifCollection will use stats
     * in the message sent to system server for the notification's dismissal.
     */
    interface OnEndDismissInterception {
        void onEndDismissInterception(
                NotifDismissInterceptor interceptor,
                NotificationEntry entry,
                DismissedByUserStats stats);
    }
}
