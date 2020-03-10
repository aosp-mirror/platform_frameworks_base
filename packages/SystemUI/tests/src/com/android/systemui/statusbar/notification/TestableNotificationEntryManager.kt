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

package com.android.systemui.statusbar.notification

import com.android.systemui.statusbar.FeatureFlags
import com.android.systemui.statusbar.NotificationPresenter
import com.android.systemui.statusbar.NotificationRemoteInputManager
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationRankingManager
import com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinder
import com.android.systemui.statusbar.phone.NotificationGroupManager
import com.android.systemui.util.leak.LeakDetector
import dagger.Lazy
import java.util.concurrent.CountDownLatch

/**
 * Enable some test capabilities for NEM without making everything public on the base class
 */
class TestableNotificationEntryManager(
    logger: NotificationEntryManagerLogger,
    gm: NotificationGroupManager,
    rm: NotificationRankingManager,
    ke: KeyguardEnvironment,
    ff: FeatureFlags,
    rb: Lazy<NotificationRowBinder>,
    notificationRemoteInputManagerLazy: Lazy<NotificationRemoteInputManager>,
    leakDetector: LeakDetector,
    fgsFeatureController: ForegroundServiceDismissalFeatureController
) : NotificationEntryManager(logger, gm, rm, ke, ff, rb, notificationRemoteInputManagerLazy,
        leakDetector, fgsFeatureController) {

    public var countDownLatch: CountDownLatch = CountDownLatch(1)

    override fun onAsyncInflationFinished(entry: NotificationEntry) {
        super.onAsyncInflationFinished(entry)
        countDownLatch.countDown()
    }

    fun setUpForTest(
        presenter: NotificationPresenter?
    ) {
        super.setUpWithPresenter(presenter)
    }

    fun setActiveNotificationList(activeList: List<NotificationEntry>) {
        mSortedAndFiltered.clear()
        mSortedAndFiltered.addAll(activeList)
    }
}
