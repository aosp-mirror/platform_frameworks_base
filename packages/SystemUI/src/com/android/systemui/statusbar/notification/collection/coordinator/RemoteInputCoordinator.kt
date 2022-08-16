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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification.collection.coordinator

import android.os.Handler
import android.service.notification.NotificationListenerService.REASON_CANCEL
import android.service.notification.NotificationListenerService.REASON_CLICK
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.systemui.Dumpable
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.NotificationRemoteInputManager
import com.android.systemui.statusbar.NotificationRemoteInputManager.RemoteInputListener
import com.android.systemui.statusbar.RemoteInputController
import com.android.systemui.statusbar.RemoteInputNotificationRebuilder
import com.android.systemui.statusbar.SmartReplyController
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.notifcollection.InternalNotifUpdater
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifLifetimeExtender
import com.android.systemui.statusbar.notification.collection.notifcollection.SelfTrackingLifetimeExtender
import java.io.PrintWriter
import javax.inject.Inject

private const val TAG = "RemoteInputCoordinator"

/**
 * How long to wait before auto-dismissing a notification that was kept for active remote input, and
 * has now sent a remote input. We auto-dismiss, because the app may not cannot cancel
 * these given that they technically don't exist anymore. We wait a bit in case the app issues
 * an update, and to also give the other lifetime extenders a beat to decide they want it.
 */
private const val REMOTE_INPUT_ACTIVE_EXTENDER_AUTO_CANCEL_DELAY: Long = 500

/**
 * How long to wait before releasing a lifetime extension when requested to do so due to a user
 * interaction (such as tapping another action).
 * We wait a bit in case the app issues an update in response to the action, but not too long or we
 * risk appearing unresponsive to the user.
 */
private const val REMOTE_INPUT_EXTENDER_RELEASE_DELAY: Long = 200

/** Whether this class should print spammy debug logs */
private val DEBUG: Boolean by lazy { Log.isLoggable(TAG, Log.DEBUG) }

@CoordinatorScope
class RemoteInputCoordinator @Inject constructor(
    dumpManager: DumpManager,
    private val mRebuilder: RemoteInputNotificationRebuilder,
    private val mNotificationRemoteInputManager: NotificationRemoteInputManager,
    @Main private val mMainHandler: Handler,
    private val mSmartReplyController: SmartReplyController
) : Coordinator, RemoteInputListener, Dumpable {

    @VisibleForTesting val mRemoteInputHistoryExtender = RemoteInputHistoryExtender()
    @VisibleForTesting val mSmartReplyHistoryExtender = SmartReplyHistoryExtender()
    @VisibleForTesting val mRemoteInputActiveExtender = RemoteInputActiveExtender()
    private val mRemoteInputLifetimeExtenders = listOf(
            mRemoteInputHistoryExtender,
            mSmartReplyHistoryExtender,
            mRemoteInputActiveExtender
    )

    private lateinit var mNotifUpdater: InternalNotifUpdater

    init {
        dumpManager.registerDumpable(this)
    }

    fun getLifetimeExtenders(): List<NotifLifetimeExtender> = mRemoteInputLifetimeExtenders

    override fun attach(pipeline: NotifPipeline) {
        mNotificationRemoteInputManager.setRemoteInputListener(this)
        mRemoteInputLifetimeExtenders.forEach { pipeline.addNotificationLifetimeExtender(it) }
        mNotifUpdater = pipeline.getInternalNotifUpdater(TAG)
        pipeline.addCollectionListener(mCollectionListener)
    }

    val mCollectionListener = object : NotifCollectionListener {
        override fun onEntryUpdated(entry: NotificationEntry, fromSystem: Boolean) {
            if (DEBUG) {
                Log.d(TAG, "mCollectionListener.onEntryUpdated(entry=${entry.key}," +
                        " fromSystem=$fromSystem)")
            }
            if (fromSystem) {
                // Mark smart replies as sent whenever a notification is updated by the app,
                // otherwise the smart replies are never marked as sent.
                mSmartReplyController.stopSending(entry)
            }
        }

        override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
            if (DEBUG) Log.d(TAG, "mCollectionListener.onEntryRemoved(entry=${entry.key})")
            // We're removing the notification, the smart reply controller can forget about it.
            // TODO(b/145659174): track 'sending' state on the entry to avoid having to clear it.
            mSmartReplyController.stopSending(entry)

            // When we know the entry will not be lifetime extended, clean up the remote input view
            // TODO: Share code with NotifCollection.cannotBeLifetimeExtended
            if (reason == REASON_CANCEL || reason == REASON_CLICK) {
                mNotificationRemoteInputManager.cleanUpRemoteInputForUserRemoval(entry)
            }
        }
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        mRemoteInputLifetimeExtenders.forEach { it.dump(pw, args) }
    }

    override fun onRemoteInputSent(entry: NotificationEntry) {
        if (DEBUG) Log.d(TAG, "onRemoteInputSent(entry=${entry.key})")
        // These calls effectively ensure the freshness of the lifetime extensions.
        // NOTE: This is some trickery! By removing the lifetime extensions when we know they should
        // be immediately re-upped, we ensure that the side-effects of the lifetime extenders get to
        // fire again, thus ensuring that we add subsequent replies to the notification.
        mRemoteInputHistoryExtender.endLifetimeExtension(entry.key)
        mSmartReplyHistoryExtender.endLifetimeExtension(entry.key)

        // If we're extending for remote input being active, then from the apps point of
        // view it is already canceled, so we'll need to cancel it on the apps behalf
        // now that a reply has been sent. However, delay so that the app has time to posts an
        // update in the mean time, and to give another lifetime extender time to pick it up.
        mRemoteInputActiveExtender.endLifetimeExtensionAfterDelay(entry.key,
                REMOTE_INPUT_ACTIVE_EXTENDER_AUTO_CANCEL_DELAY)
    }

    private fun onSmartReplySent(entry: NotificationEntry, reply: CharSequence) {
        if (DEBUG) Log.d(TAG, "onSmartReplySent(entry=${entry.key})")
        val newSbn = mRebuilder.rebuildForSendingSmartReply(entry, reply)
        mNotifUpdater.onInternalNotificationUpdate(newSbn,
                "Adding smart reply spinner for sent")

        // If we're extending for remote input being active, then from the apps point of
        // view it is already canceled, so we'll need to cancel it on the apps behalf
        // now that a reply has been sent. However, delay so that the app has time to posts an
        // update in the mean time, and to give another lifetime extender time to pick it up.
        mRemoteInputActiveExtender.endLifetimeExtensionAfterDelay(entry.key,
                REMOTE_INPUT_ACTIVE_EXTENDER_AUTO_CANCEL_DELAY)
    }

    override fun onPanelCollapsed() {
        mRemoteInputActiveExtender.endAllLifetimeExtensions()
    }

    override fun isNotificationKeptForRemoteInputHistory(key: String) =
            mRemoteInputHistoryExtender.isExtending(key) ||
                    mSmartReplyHistoryExtender.isExtending(key)

    override fun releaseNotificationIfKeptForRemoteInputHistory(entry: NotificationEntry) {
        if (DEBUG) Log.d(TAG, "releaseNotificationIfKeptForRemoteInputHistory(entry=${entry.key})")
        mRemoteInputHistoryExtender.endLifetimeExtensionAfterDelay(entry.key,
                REMOTE_INPUT_EXTENDER_RELEASE_DELAY)
        mSmartReplyHistoryExtender.endLifetimeExtensionAfterDelay(entry.key,
                REMOTE_INPUT_EXTENDER_RELEASE_DELAY)
        mRemoteInputActiveExtender.endLifetimeExtensionAfterDelay(entry.key,
                REMOTE_INPUT_EXTENDER_RELEASE_DELAY)
    }

    override fun setRemoteInputController(remoteInputController: RemoteInputController) {
        mSmartReplyController.setCallback(this::onSmartReplySent)
    }

    @VisibleForTesting
    inner class RemoteInputHistoryExtender :
            SelfTrackingLifetimeExtender(TAG, "RemoteInputHistory", DEBUG, mMainHandler) {

        override fun queryShouldExtendLifetime(entry: NotificationEntry): Boolean =
                mNotificationRemoteInputManager.shouldKeepForRemoteInputHistory(entry)

        override fun onStartedLifetimeExtension(entry: NotificationEntry) {
            val newSbn = mRebuilder.rebuildForRemoteInputReply(entry)
            entry.onRemoteInputInserted()
            mNotifUpdater.onInternalNotificationUpdate(newSbn,
                    "Extending lifetime of notification with remote input")
            // TODO: Check if the entry was removed due perhaps to an inflation exception?
        }
    }

    @VisibleForTesting
    inner class SmartReplyHistoryExtender :
            SelfTrackingLifetimeExtender(TAG, "SmartReplyHistory", DEBUG, mMainHandler) {

        override fun queryShouldExtendLifetime(entry: NotificationEntry): Boolean =
                mNotificationRemoteInputManager.shouldKeepForSmartReplyHistory(entry)

        override fun onStartedLifetimeExtension(entry: NotificationEntry) {
            val newSbn = mRebuilder.rebuildForCanceledSmartReplies(entry)
            mSmartReplyController.stopSending(entry)
            mNotifUpdater.onInternalNotificationUpdate(newSbn,
                    "Extending lifetime of notification with smart reply")
            // TODO: Check if the entry was removed due perhaps to an inflation exception?
        }

        override fun onCanceledLifetimeExtension(entry: NotificationEntry) {
            // TODO(b/145659174): track 'sending' state on the entry to avoid having to clear it.
            mSmartReplyController.stopSending(entry)
        }
    }

    @VisibleForTesting
    inner class RemoteInputActiveExtender :
            SelfTrackingLifetimeExtender(TAG, "RemoteInputActive", DEBUG, mMainHandler) {

        override fun queryShouldExtendLifetime(entry: NotificationEntry): Boolean =
                mNotificationRemoteInputManager.isRemoteInputActive(entry)
    }
}