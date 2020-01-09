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
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.NotificationRankingManager
import com.android.systemui.statusbar.notification.logging.NotifLog
import com.android.systemui.statusbar.notification.stack.NotificationListContainer
import com.android.systemui.statusbar.phone.HeadsUpManagerPhone
import com.android.systemui.statusbar.phone.NotificationGroupManager

import java.util.concurrent.CountDownLatch

/**
 * Enable some test capabilities for NEM without making everything public on the base class
 */
class TestableNotificationEntryManager(
    log: NotifLog,
    gm: NotificationGroupManager,
    rm: NotificationRankingManager,
    ke: KeyguardEnvironment,
    ff: FeatureFlags
) : NotificationEntryManager(log, gm, rm, ke, ff) {

    public var countDownLatch: CountDownLatch = CountDownLatch(1)

    override fun onAsyncInflationFinished(entry: NotificationEntry?, inflatedFlags: Int) {
        super.onAsyncInflationFinished(entry, inflatedFlags)
        countDownLatch.countDown()
    }

    fun setUpForTest(
        presenter: NotificationPresenter?,
        listContainer: NotificationListContainer?,
        headsUpManager: HeadsUpManagerPhone?
    ) {
        super.setUpWithPresenter(presenter, listContainer, headsUpManager)
    }

    fun setActiveNotificationList(activeList: List<NotificationEntry>) {
        mSortedAndFiltered.clear()
        mSortedAndFiltered.addAll(activeList)
    }
}
