/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.coordinator;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.StatusBarState;
import com.android.systemui.statusbar.notification.SectionHeaderVisibilityProvider;
import com.android.systemui.statusbar.notification.collection.NotifPipeline;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope;
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter;
import com.android.systemui.statusbar.notification.collection.provider.HighPriorityProvider;
import com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProvider;

import javax.inject.Inject;

/**
 * Filters low priority and privacy-sensitive notifications from the lockscreen, and hides section
 * headers on the lockscreen.
 */
@CoordinatorScope
public class KeyguardCoordinator implements Coordinator {
    private static final String TAG = "KeyguardCoordinator";
    private final StatusBarStateController mStatusBarStateController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final HighPriorityProvider mHighPriorityProvider;
    private final SectionHeaderVisibilityProvider mSectionHeaderVisibilityProvider;
    private final KeyguardNotificationVisibilityProvider mKeyguardNotificationVisibilityProvider;
    private final SharedCoordinatorLogger mLogger;

    @Inject
    public KeyguardCoordinator(
            StatusBarStateController statusBarStateController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            HighPriorityProvider highPriorityProvider,
            SectionHeaderVisibilityProvider sectionHeaderVisibilityProvider,
            KeyguardNotificationVisibilityProvider keyguardNotificationVisibilityProvider,
            SharedCoordinatorLogger logger) {
        mStatusBarStateController = statusBarStateController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mHighPriorityProvider = highPriorityProvider;
        mSectionHeaderVisibilityProvider = sectionHeaderVisibilityProvider;
        mKeyguardNotificationVisibilityProvider = keyguardNotificationVisibilityProvider;
        mLogger = logger;
    }

    @Override
    public void attach(NotifPipeline pipeline) {

        setupInvalidateNotifListCallbacks();
        // Filter at the "finalize" stage so that views remain bound by PreparationCoordinator
        pipeline.addFinalizeFilter(mNotifFilter);
        mKeyguardNotificationVisibilityProvider
                .addOnStateChangedListener(this::invalidateListFromFilter);
        updateSectionHeadersVisibility();
    }

    private final NotifFilter mNotifFilter = new NotifFilter(TAG) {
        @Override
        public boolean shouldFilterOut(NotificationEntry entry, long now) {
            return mKeyguardNotificationVisibilityProvider.shouldHideNotification(entry);
        }
    };

    // TODO(b/206118999): merge this class with SensitiveContentCoordinator which also depends on
    // these same updates
    private void setupInvalidateNotifListCallbacks() {

    }

    private void invalidateListFromFilter(String reason) {
        mLogger.logKeyguardCoordinatorInvalidated(reason);
        updateSectionHeadersVisibility();
        mNotifFilter.invalidateList();
    }

    private void updateSectionHeadersVisibility() {
        boolean onKeyguard = mStatusBarStateController.getState() == StatusBarState.KEYGUARD;
        boolean neverShowSections = mSectionHeaderVisibilityProvider.getNeverShowSectionHeaders();
        boolean showSections = !onKeyguard && !neverShowSections;
        mSectionHeaderVisibilityProvider.setSectionHeadersVisible(showSections);
    }
}
