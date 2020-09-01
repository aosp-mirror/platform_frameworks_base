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
import com.android.systemui.statusbar.notification.collection.NotifCollection.CancellationReason;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

/**
 * A way for other code to temporarily extend the lifetime of a notification after it has been
 * retracted. See {@link NotifCollection#addNotificationLifetimeExtender(NotifLifetimeExtender)}.
 */
public interface NotifLifetimeExtender {
    /** Name to associate with this extender (for the purposes of debugging) */
    String getName();

    /**
     * Called on the extender immediately after it has been registered. The extender should hang on
     * to this callback and execute it whenever it no longer needs to extend the lifetime of a
     * notification.
     */
    void setCallback(OnEndLifetimeExtensionCallback callback);

    /**
     * Called by the NotifCollection whenever a notification has been retracted (by the app) or
     * dismissed (by the user). If the extender returns true, it is considered to be extending the
     * lifetime of that notification. Lifetime-extended notifications are kept around until all
     * active extenders expire their extension by calling onEndLifetimeExtension(). This method is
     * called on all lifetime extenders even if earlier ones return true (in other words, multiple
     * lifetime extenders can be extending a notification at the same time).
     */
    boolean shouldExtendLifetime(NotificationEntry entry, @CancellationReason int reason);

    /**
     * Called by the NotifCollection to inform a lifetime extender that its extension of a notif
     * is no longer valid (usually because the notif has been reposted and so no longer needs
     * lifetime extension). The extender should clean up any references it has to the notif in
     * question.
     */
    void cancelLifetimeExtension(NotificationEntry entry);

    /** Callback for notifying the NotifCollection that a lifetime extension has expired. */
    interface OnEndLifetimeExtensionCallback {
        void onEndLifetimeExtension(NotifLifetimeExtender extender, NotificationEntry entry);
    }
}
