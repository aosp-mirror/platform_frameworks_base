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

package com.android.systemui.car.notification;

import android.os.RemoteException;
import android.util.ArraySet;
import android.util.Log;

import com.android.car.notification.AlertEntry;
import com.android.car.notification.NotificationDataManager;
import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.dagger.qualifiers.UiBackground;

import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Handles notification logging, in particular, logging which notifications are visible and which
 * are not.
 */
@Singleton
public class NotificationVisibilityLogger {

    private static final String TAG = "NotificationVisibilityLogger";

    private final ArraySet<NotificationVisibility> mCurrentlyVisible = new ArraySet<>();
    private final ArraySet<NotificationVisibility> mNewlyVisible = new ArraySet<>();
    private final ArraySet<NotificationVisibility> mPreviouslyVisible = new ArraySet<>();
    private final ArraySet<NotificationVisibility> mTmpCurrentlyVisible = new ArraySet<>();

    private final IStatusBarService mBarService;
    private final Executor mUiBgExecutor;
    private final NotificationDataManager mNotificationDataManager;

    private boolean mIsVisible;

    private final Runnable mVisibilityReporter = new Runnable() {

        @Override
        public void run() {
            if (mIsVisible) {
                int count = mNotificationDataManager.getVisibleNotifications().size();
                for (AlertEntry alertEntry : mNotificationDataManager.getVisibleNotifications()) {
                    NotificationVisibility visObj = NotificationVisibility.obtain(
                            alertEntry.getKey(),
                            /* rank= */ -1,
                            count,
                            mIsVisible,
                            NotificationVisibility.NotificationLocation.LOCATION_MAIN_AREA);
                    mTmpCurrentlyVisible.add(visObj);
                    if (!mCurrentlyVisible.contains(visObj)) {
                        mNewlyVisible.add(visObj);
                    }
                }
            }
            mPreviouslyVisible.addAll(mCurrentlyVisible);
            mPreviouslyVisible.removeAll(mTmpCurrentlyVisible);
            onNotificationVisibilityChanged(mNewlyVisible, mPreviouslyVisible);

            recycleAllVisibilityObjects(mCurrentlyVisible);
            mCurrentlyVisible.addAll(mTmpCurrentlyVisible);

            recycleAllVisibilityObjects(mPreviouslyVisible);
            recycleAllVisibilityObjects(mNewlyVisible);
            recycleAllVisibilityObjects(mTmpCurrentlyVisible);
        }
    };

    @Inject
    public NotificationVisibilityLogger(
            @UiBackground Executor uiBgExecutor,
            IStatusBarService barService,
            NotificationDataManager notificationDataManager) {
        mUiBgExecutor = uiBgExecutor;
        mBarService = barService;
        mNotificationDataManager = notificationDataManager;
    }

    /** Triggers a visibility report update to be sent to StatusBarService. */
    public void log(boolean isVisible) {
        mIsVisible = isVisible;
        mUiBgExecutor.execute(mVisibilityReporter);
    }

    /** Stops logging, clearing all visibility objects. */
    public void stop() {
        recycleAllVisibilityObjects(mCurrentlyVisible);
    }

    /**
     * Notify StatusBarService of change in notifications' visibility.
     */
    private void onNotificationVisibilityChanged(
            Set<NotificationVisibility> newlyVisible, Set<NotificationVisibility> noLongerVisible) {
        if (newlyVisible.isEmpty() && noLongerVisible.isEmpty()) {
            return;
        }

        try {
            mBarService.onNotificationVisibilityChanged(
                    cloneVisibilitiesAsArr(newlyVisible), cloneVisibilitiesAsArr(noLongerVisible));
        } catch (RemoteException e) {
            // Won't fail unless the world has ended.
            Log.e(TAG, "Failed to notify StatusBarService of notification visibility change");
        }
    }

    /**
     * Clears array and recycles NotificationVisibility objects for reuse.
     */
    private static void recycleAllVisibilityObjects(ArraySet<NotificationVisibility> array) {
        for (int i = 0; i < array.size(); i++) {
            array.valueAt(i).recycle();
        }
        array.clear();
    }

    /**
     * Converts Set of NotificationVisibility objects to primitive array.
     */
    private static NotificationVisibility[] cloneVisibilitiesAsArr(Set<NotificationVisibility> c) {
        NotificationVisibility[] array = new NotificationVisibility[c.size()];
        int i = 0;
        for (NotificationVisibility nv : c) {
            if (nv != null) {
                array[i] = nv.clone();
            }
            i++;
        }
        return array;
    }
}
