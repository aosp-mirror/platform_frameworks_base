/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.systemui.statusbar.notification;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.ArrayMap;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.legacy.VisualStabilityManager;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.dagger.NotificationsModule;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * NotificationEntryManager is responsible for the adding, removing, and updating of
 * {@link NotificationEntry}s. It also handles tasks such as their inflation and their interaction
 * with other Notification.*Manager objects.
 *
 * We track notification entries through this lifecycle:
 *      1. Pending
 *      2. Active
 *      3. Sorted / filtered (visible)
 *
 * Every entry spends some amount of time in the pending state, while it is being inflated. Once
 * inflated, an entry moves into the active state, where it _could_ potentially be shown to the
 * user. After an entry makes its way into the active state, we sort and filter the entire set to
 * repopulate the visible set.
 */
public class NotificationEntryManager implements VisualStabilityManager.Callback {

    private final NotificationEntryManagerLogger mLogger;

    /** Pending notifications are ones awaiting inflation */
    @VisibleForTesting
    protected final HashMap<String, NotificationEntry> mPendingNotifications = new HashMap<>();
    /**
     * Active notifications have been inflated / prepared and could become visible, but may get
     * filtered out if for instance they are not for the current user
     */
    private final ArrayMap<String, NotificationEntry> mActiveNotifications = new ArrayMap<>();
    /** This is the list of "active notifications for this user in this context" */
    @VisibleForTesting
    protected final ArrayList<NotificationEntry> mSortedAndFiltered = new ArrayList<>();
    private final List<NotifCollectionListener> mNotifCollectionListeners = new ArrayList<>();
    private final List<NotificationEntryListener> mNotificationEntryListeners = new ArrayList<>();

    /**
     * Injected constructor. See {@link NotificationsModule}.
     */
    public NotificationEntryManager(NotificationEntryManagerLogger logger) {
        mLogger = logger;
    }

    /** Adds a {@link NotificationEntryListener}. */
    public void addNotificationEntryListener(NotificationEntryListener listener) {
        mNotificationEntryListeners.add(listener);
    }

    /**
     * Removes a {@link NotificationEntryListener} previously registered via
     * {@link #addNotificationEntryListener(NotificationEntryListener)}.
     */
    public void removeNotificationEntryListener(NotificationEntryListener listener) {
        mNotificationEntryListeners.remove(listener);
    }

    @Override
    public void onChangeAllowed() {
        updateNotifications("reordering is now allowed");
    }

    /**
     * Update the notifications
     * @param reason why the notifications are updating
     */
    public void updateNotifications(String reason) {
        mLogger.logUseWhileNewPipelineActive("updateNotifications", reason);
    }

    /*
     * -----
     * Annexed from NotificationData below:
     * Some of these methods may be redundant but require some reworking to remove. For now
     * we'll try to keep the behavior the same and can simplify these interfaces in another pass
     */

    /** Resorts / filters the current notification set with the current RankingMap */
    public void reapplyFilterAndSort(String reason) {
        mLogger.logUseWhileNewPipelineActive("reapplyFilterAndSort", reason);
    }

    /** dump the current active notification list. Called from CentralSurfaces */
    public void dump(PrintWriter pw, String indent) {
        pw.println("NotificationEntryManager (Legacy)");
        int filteredLen = mSortedAndFiltered.size();
        pw.print(indent);
        pw.println("active notifications: " + filteredLen);
        int active;
        for (active = 0; active < filteredLen; active++) {
            NotificationEntry e = mSortedAndFiltered.get(active);
            dumpEntry(pw, indent, active, e);
        }
        synchronized (mActiveNotifications) {
            int totalLen = mActiveNotifications.size();
            pw.print(indent);
            pw.println("inactive notifications: " + (totalLen - active));
            int inactiveCount = 0;
            for (int i = 0; i < totalLen; i++) {
                NotificationEntry entry = mActiveNotifications.valueAt(i);
                if (!mSortedAndFiltered.contains(entry)) {
                    dumpEntry(pw, indent, inactiveCount, entry);
                    inactiveCount++;
                }
            }
        }
    }

    private void dumpEntry(PrintWriter pw, String indent, int i, NotificationEntry e) {
        pw.print(indent);
        pw.println("  [" + i + "] key=" + e.getKey() + " icon=" + e.getIcons().getStatusBarIcon());
        StatusBarNotification n = e.getSbn();
        pw.print(indent);
        pw.println("      pkg=" + n.getPackageName() + " id=" + n.getId() + " importance="
                + e.getRanking().getImportance());
        pw.print(indent);
        pw.println("      notification=" + n.getNotification());
    }

    public void addCollectionListener(@NonNull NotifCollectionListener listener) {
        mNotifCollectionListeners.add(listener);
    }

    public void removeCollectionListener(@NonNull NotifCollectionListener listener) {
        mNotifCollectionListeners.remove(listener);
    }

    /*
     * End annexation
     * -----
     */


    /**
     * Provides access to keyguard state and user settings dependent data.
     */
    public interface KeyguardEnvironment {
        /** true if the device is provisioned (should always be true in practice) */
        boolean isDeviceProvisioned();
        /** true if the notification is for the current profiles */
        boolean isNotificationForCurrentProfiles(StatusBarNotification sbn);
    }

    /**
     * Used when a notification is removed and it doesn't have a reason that maps to one of the
     * reasons defined in NotificationListenerService
     * (e.g. {@link NotificationListenerService#REASON_CANCEL})
     */
    public static final int UNDEFINED_DISMISS_REASON = 0;
}
