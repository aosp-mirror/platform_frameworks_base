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

package com.android.systemui.statusbar.notification.headsup;

import androidx.annotation.NonNull;

import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.coordinator.HeadsUpCoordinator;
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener;
import com.android.systemui.statusbar.notification.interruption.NotificationAlertingManager;
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.OnHeadsUpChangedListener;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Controller class for old pipeline heads up view binding. It listens to
 * {@link NotificationEntryManager} entry events and appropriately binds or unbinds the heads up
 * view.
 *
 * This has a subtle contract with {@link NotificationAlertingManager} where this controller handles
 * the heads up binding, but {@link NotificationAlertingManager} listens for general inflation
 * events to actually mark it heads up/update. In the new pipeline, we combine the classes.
 * See {@link HeadsUpCoordinator}.
 */
@Singleton
public class HeadsUpBindController {
    private final HeadsUpViewBinder mHeadsUpViewBinder;
    private final NotificationInterruptStateProvider mInterruptStateProvider;

    @Inject
    HeadsUpBindController(
            HeadsUpViewBinder headsUpViewBinder,
            NotificationInterruptStateProvider notificationInterruptStateProvider) {
        mInterruptStateProvider = notificationInterruptStateProvider;
        mHeadsUpViewBinder = headsUpViewBinder;
    }

    /**
     * Attach this controller and add its listeners.
     */
    public void attach(
            NotificationEntryManager entryManager,
            HeadsUpManager headsUpManager) {
        entryManager.addCollectionListener(mCollectionListener);
        headsUpManager.addListener(mOnHeadsUpChangedListener);
    }

    private NotifCollectionListener mCollectionListener = new NotifCollectionListener() {
        @Override
        public void onEntryAdded(NotificationEntry entry) {
            if (mInterruptStateProvider.shouldHeadsUp(entry)) {
                mHeadsUpViewBinder.bindHeadsUpView(entry, null);
            }
        }

        @Override
        public void onEntryUpdated(NotificationEntry entry) {
            if (mInterruptStateProvider.shouldHeadsUp(entry)) {
                mHeadsUpViewBinder.bindHeadsUpView(entry, null);
            }
        }

        @Override
        public void onEntryCleanUp(NotificationEntry entry) {
            mHeadsUpViewBinder.abortBindCallback(entry);
        }
    };

    private OnHeadsUpChangedListener mOnHeadsUpChangedListener  = new OnHeadsUpChangedListener() {
        @Override
        public void onHeadsUpStateChanged(@NonNull NotificationEntry entry, boolean isHeadsUp) {
            if (!isHeadsUp) {
                mHeadsUpViewBinder.unbindHeadsUpView(entry);
            }
        }
    };
}
