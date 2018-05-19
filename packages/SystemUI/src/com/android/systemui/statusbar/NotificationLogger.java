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
package com.android.systemui.statusbar;

import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.Dependency;
import com.android.systemui.UiOffloadThread;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;

/**
 * Handles notification logging, in particular, logging which notifications are visible and which
 * are not.
 */
public class NotificationLogger {
    private static final String TAG = "NotificationLogger";

    /** The minimum delay in ms between reports of notification visibility. */
    private static final int VISIBILITY_REPORT_MIN_DELAY_MS = 500;

    /** Keys of notifications currently visible to the user. */
    private final ArraySet<NotificationVisibility> mCurrentlyVisibleNotifications =
            new ArraySet<>();

    // Dependencies:
    private final NotificationListenerService mNotificationListener =
            Dependency.get(NotificationListener.class);
    private final UiOffloadThread mUiOffloadThread = Dependency.get(UiOffloadThread.class);

    protected NotificationEntryManager mEntryManager;
    protected Handler mHandler = new Handler();
    protected IStatusBarService mBarService;
    private long mLastVisibilityReportUptimeMs;
    private NotificationListContainer mListContainer;

    protected final OnChildLocationsChangedListener mNotificationLocationsChangedListener =
            new OnChildLocationsChangedListener() {
                @Override
                public void onChildLocationsChanged() {
                    if (mHandler.hasCallbacks(mVisibilityReporter)) {
                        // Visibilities will be reported when the existing
                        // callback is executed.
                        return;
                    }
                    // Calculate when we're allowed to run the visibility
                    // reporter. Note that this timestamp might already have
                    // passed. That's OK, the callback will just be executed
                    // ASAP.
                    long nextReportUptimeMs =
                            mLastVisibilityReportUptimeMs + VISIBILITY_REPORT_MIN_DELAY_MS;
                    mHandler.postAtTime(mVisibilityReporter, nextReportUptimeMs);
                }
            };

    // Tracks notifications currently visible in mNotificationStackScroller and
    // emits visibility events via NoMan on changes.
    protected final Runnable mVisibilityReporter = new Runnable() {
        private final ArraySet<NotificationVisibility> mTmpNewlyVisibleNotifications =
                new ArraySet<>();
        private final ArraySet<NotificationVisibility> mTmpCurrentlyVisibleNotifications =
                new ArraySet<>();
        private final ArraySet<NotificationVisibility> mTmpNoLongerVisibleNotifications =
                new ArraySet<>();

        @Override
        public void run() {
            mLastVisibilityReportUptimeMs = SystemClock.uptimeMillis();

            // 1. Loop over mNotificationData entries:
            //   A. Keep list of visible notifications.
            //   B. Keep list of previously hidden, now visible notifications.
            // 2. Compute no-longer visible notifications by removing currently
            //    visible notifications from the set of previously visible
            //    notifications.
            // 3. Report newly visible and no-longer visible notifications.
            // 4. Keep currently visible notifications for next report.
            ArrayList<NotificationData.Entry> activeNotifications = mEntryManager
                    .getNotificationData().getActiveNotifications();
            int N = activeNotifications.size();
            for (int i = 0; i < N; i++) {
                NotificationData.Entry entry = activeNotifications.get(i);
                String key = entry.notification.getKey();
                boolean isVisible = mListContainer.isInVisibleLocation(entry.row);
                NotificationVisibility visObj = NotificationVisibility.obtain(key, i, N, isVisible);
                boolean previouslyVisible = mCurrentlyVisibleNotifications.contains(visObj);
                if (isVisible) {
                    // Build new set of visible notifications.
                    mTmpCurrentlyVisibleNotifications.add(visObj);
                    if (!previouslyVisible) {
                        mTmpNewlyVisibleNotifications.add(visObj);
                    }
                } else {
                    // release object
                    visObj.recycle();
                }
            }
            mTmpNoLongerVisibleNotifications.addAll(mCurrentlyVisibleNotifications);
            mTmpNoLongerVisibleNotifications.removeAll(mTmpCurrentlyVisibleNotifications);

            logNotificationVisibilityChanges(
                    mTmpNewlyVisibleNotifications, mTmpNoLongerVisibleNotifications);

            recycleAllVisibilityObjects(mCurrentlyVisibleNotifications);
            mCurrentlyVisibleNotifications.addAll(mTmpCurrentlyVisibleNotifications);

            recycleAllVisibilityObjects(mTmpNoLongerVisibleNotifications);
            mTmpCurrentlyVisibleNotifications.clear();
            mTmpNewlyVisibleNotifications.clear();
            mTmpNoLongerVisibleNotifications.clear();
        }
    };

    public NotificationLogger() {
        mBarService = IStatusBarService.Stub.asInterface(
                ServiceManager.getService(Context.STATUS_BAR_SERVICE));
    }

    public void setUpWithEntryManager(NotificationEntryManager entryManager,
            NotificationListContainer listContainer) {
        mEntryManager = entryManager;
        mListContainer = listContainer;
    }

    public void stopNotificationLogging() {
        // Report all notifications as invisible and turn down the
        // reporter.
        if (!mCurrentlyVisibleNotifications.isEmpty()) {
            logNotificationVisibilityChanges(
                    Collections.emptyList(), mCurrentlyVisibleNotifications);
            recycleAllVisibilityObjects(mCurrentlyVisibleNotifications);
        }
        mHandler.removeCallbacks(mVisibilityReporter);
        mListContainer.setChildLocationsChangedListener(null);
    }

    public void startNotificationLogging() {
        mListContainer.setChildLocationsChangedListener(mNotificationLocationsChangedListener);
        // Some transitions like mVisibleToUser=false -> mVisibleToUser=true don't
        // cause the scroller to emit child location events. Hence generate
        // one ourselves to guarantee that we're reporting visible
        // notifications.
        // (Note that in cases where the scroller does emit events, this
        // additional event doesn't break anything.)
        mNotificationLocationsChangedListener.onChildLocationsChanged();
    }

    private void logNotificationVisibilityChanges(
            Collection<NotificationVisibility> newlyVisible,
            Collection<NotificationVisibility> noLongerVisible) {
        if (newlyVisible.isEmpty() && noLongerVisible.isEmpty()) {
            return;
        }
        final NotificationVisibility[] newlyVisibleAr = cloneVisibilitiesAsArr(newlyVisible);
        final NotificationVisibility[] noLongerVisibleAr = cloneVisibilitiesAsArr(noLongerVisible);

        mUiOffloadThread.submit(() -> {
            try {
                mBarService.onNotificationVisibilityChanged(newlyVisibleAr, noLongerVisibleAr);
            } catch (RemoteException e) {
                // Ignore.
            }

            final int N = newlyVisible.size();
            if (N > 0) {
                String[] newlyVisibleKeyAr = new String[N];
                for (int i = 0; i < N; i++) {
                    newlyVisibleKeyAr[i] = newlyVisibleAr[i].key;
                }

                // TODO: Call NotificationEntryManager to do this, once it exists.
                // TODO: Consider not catching all runtime exceptions here.
                try {
                    mNotificationListener.setNotificationsShown(newlyVisibleKeyAr);
                } catch (RuntimeException e) {
                    Log.d(TAG, "failed setNotificationsShown: ", e);
                }
            }
            recycleAllVisibilityObjects(newlyVisibleAr);
            recycleAllVisibilityObjects(noLongerVisibleAr);
        });
    }

    private void recycleAllVisibilityObjects(ArraySet<NotificationVisibility> array) {
        final int N = array.size();
        for (int i = 0 ; i < N; i++) {
            array.valueAt(i).recycle();
        }
        array.clear();
    }

    private void recycleAllVisibilityObjects(NotificationVisibility[] array) {
        final int N = array.length;
        for (int i = 0 ; i < N; i++) {
            if (array[i] != null) {
                array[i].recycle();
            }
        }
    }

    private NotificationVisibility[] cloneVisibilitiesAsArr(Collection<NotificationVisibility> c) {

        final NotificationVisibility[] array = new NotificationVisibility[c.size()];
        int i = 0;
        for(NotificationVisibility nv: c) {
            if (nv != null) {
                array[i] = nv.clone();
            }
            i++;
        }
        return array;
    }

    @VisibleForTesting
    public Runnable getVisibilityReporter() {
        return mVisibilityReporter;
    }

    /**
     * A listener that is notified when some child locations might have changed.
     */
    public interface OnChildLocationsChangedListener {
        void onChildLocationsChanged();
    }
}
