/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.collection.coordinator

import android.service.notification.NotificationListenerService
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.collection.render.NotifShadeEventSource
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * A coordinator which provides callbacks to a view surfaces for various events relevant to the
 * shade, such as when the user removes a notification, or when the shade is emptied.
 */
// TODO(b/204468557): Move to @CoordinatorScope
@SysUISingleton
class ShadeEventCoordinator @Inject internal constructor(
    @Main private val mMainExecutor: Executor,
    private val mLogger: ShadeEventCoordinatorLogger
) : Coordinator, NotifShadeEventSource {
    private var mNotifRemovedByUserCallback: Runnable? = null
    private var mShadeEmptiedCallback: Runnable? = null
    private var mEntryRemoved = false
    private var mEntryRemovedByUser = false

    override fun attach(pipeline: NotifPipeline) {
        pipeline.addCollectionListener(mNotifCollectionListener)
        pipeline.addOnBeforeRenderListListener(this::onBeforeRenderList)
    }

    private val mNotifCollectionListener = object : NotifCollectionListener {
        override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
            mEntryRemoved = true
            mEntryRemovedByUser =
                    reason == NotificationListenerService.REASON_CLICK ||
                    reason == NotificationListenerService.REASON_CANCEL_ALL ||
                    reason == NotificationListenerService.REASON_CANCEL
        }
    }

    override fun setNotifRemovedByUserCallback(callback: Runnable) {
        check(mNotifRemovedByUserCallback == null) { "mNotifRemovedByUserCallback already set" }
        mNotifRemovedByUserCallback = callback
    }

    override fun setShadeEmptiedCallback(callback: Runnable) {
        check(mShadeEmptiedCallback == null) { "mShadeEmptiedCallback already set" }
        mShadeEmptiedCallback = callback
    }

    private fun onBeforeRenderList(entries: List<ListEntry>) {
        if (mEntryRemoved && entries.isEmpty()) {
            mLogger.logShadeEmptied()
            // TODO(b/206023518): This was bad. Do not copy this.
            mShadeEmptiedCallback?.let { mMainExecutor.execute(it) }
        }
        if (mEntryRemoved && mEntryRemovedByUser) {
            mLogger.logNotifRemovedByUser()
            // TODO(b/206023518): This was bad. Do not copy this.
            mNotifRemovedByUserCallback?.let { mMainExecutor.execute(it) }
        }
        mEntryRemoved = false
        mEntryRemovedByUser = false
    }
}