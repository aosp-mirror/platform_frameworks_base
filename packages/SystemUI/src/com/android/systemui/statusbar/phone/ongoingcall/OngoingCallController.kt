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

package com.android.systemui.statusbar.phone.ongoingcall

import android.app.ActivityManager
import android.app.IActivityManager
import android.app.Notification
import android.app.Notification.CallStyle.CALL_TYPE_ONGOING
import android.app.PendingIntent
import android.app.UidObserver
import android.content.Context
import android.view.View
import androidx.annotation.VisibleForTesting
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.CoreStartable
import com.android.systemui.Dumpable
import com.android.systemui.Flags
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.chips.ui.view.ChipBackgroundContainer
import com.android.systemui.statusbar.chips.ui.view.ChipChronometer
import com.android.systemui.statusbar.data.repository.StatusBarModeRepositoryStore
import com.android.systemui.statusbar.gesture.SwipeStatusBarAwayGestureHandler
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.notifcollection.CommonNotifCollection
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.statusbar.notification.shared.CallType
import com.android.systemui.statusbar.phone.ongoingcall.data.repository.OngoingCallRepository
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.OngoingCallModel
import com.android.systemui.statusbar.policy.CallbackController
import com.android.systemui.statusbar.window.StatusBarWindowControllerStore
import com.android.systemui.util.time.SystemClock
import java.io.PrintWriter
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A controller to handle the ongoing call chip in the collapsed status bar. */
@SysUISingleton
class OngoingCallController
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val context: Context,
    private val ongoingCallRepository: OngoingCallRepository,
    private val notifCollection: CommonNotifCollection,
    private val activeNotificationsInteractor: ActiveNotificationsInteractor,
    private val systemClock: SystemClock,
    private val activityStarter: ActivityStarter,
    @Main private val mainExecutor: Executor,
    private val iActivityManager: IActivityManager,
    private val dumpManager: DumpManager,
    private val statusBarWindowControllerStore: StatusBarWindowControllerStore,
    private val swipeStatusBarAwayGestureHandler: SwipeStatusBarAwayGestureHandler,
    private val statusBarModeRepository: StatusBarModeRepositoryStore,
    @OngoingCallLog private val logger: LogBuffer,
) : CallbackController<OngoingCallListener>, Dumpable, CoreStartable {
    private var isFullscreen: Boolean = false
    /** Non-null if there's an active call notification. */
    private var callNotificationInfo: CallNotificationInfo? = null
    private var chipView: View? = null

    private val mListeners: MutableList<OngoingCallListener> = mutableListOf()
    private val uidObserver = CallAppUidObserver()
    private val notifListener =
        object : NotifCollectionListener {
            // Temporary workaround for b/178406514 for testing purposes.
            //
            // b/178406514 means that posting an incoming call notif then updating it to an ongoing
            // call notif does not work (SysUI never receives the update). This workaround allows us
            // to trigger the ongoing call chip when an ongoing call notif is *added* rather than
            // *updated*, allowing us to test the chip.
            //
            // TODO(b/183229367): Remove this function override when b/178406514 is fixed.
            override fun onEntryAdded(entry: NotificationEntry) {
                onEntryUpdated(entry, true)
            }

            override fun onEntryUpdated(entry: NotificationEntry) {
                StatusBarUseReposForCallChip.assertInLegacyMode()
                // We have a new call notification or our existing call notification has been
                // updated.
                // TODO(b/183229367): This likely won't work if you take a call from one app then
                //  switch to a call from another app.
                if (
                    callNotificationInfo == null && isCallNotification(entry) ||
                        (entry.sbn.key == callNotificationInfo?.key)
                ) {
                    val newOngoingCallInfo =
                        CallNotificationInfo(
                            entry.sbn.key,
                            entry.sbn.notification.getWhen(),
                            // In this old listener pattern, we don't have access to the
                            // notification icon.
                            notificationIconView = null,
                            entry.sbn.notification.contentIntent,
                            entry.sbn.uid,
                            entry.sbn.notification.extras.getInt(
                                Notification.EXTRA_CALL_TYPE,
                                -1
                            ) == CALL_TYPE_ONGOING,
                            statusBarSwipedAway = callNotificationInfo?.statusBarSwipedAway ?: false
                        )
                    if (newOngoingCallInfo == callNotificationInfo) {
                        return
                    }

                    callNotificationInfo = newOngoingCallInfo
                    if (newOngoingCallInfo.isOngoing) {
                        logger.log(
                            TAG,
                            LogLevel.DEBUG,
                            { str1 = newOngoingCallInfo.key },
                            { "Call notif *is* ongoing -> showing chip. key=$str1" },
                        )
                        updateChip()
                    } else {
                        logger.log(
                            TAG,
                            LogLevel.DEBUG,
                            { str1 = newOngoingCallInfo.key },
                            { "Call notif not ongoing -> hiding chip. key=$str1" },
                        )
                        removeChip()
                    }
                }
            }

            override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
                if (entry.sbn.key == callNotificationInfo?.key) {
                    logger.log(
                        TAG,
                        LogLevel.DEBUG,
                        { str1 = entry.sbn.key },
                        { "Call notif removed -> hiding chip. key=$str1" },
                    )
                    removeChip()
                }
            }
        }

    override fun start() {
        dumpManager.registerDumpable(this)

        if (Flags.statusBarUseReposForCallChip()) {
            scope.launch {
                // Listening to [ActiveNotificationsInteractor] instead of using
                // [NotifCollectionListener#onEntryUpdated] is better for two reasons:
                // 1. ActiveNotificationsInteractor automatically filters the notification list to
                // just notifications for the current user, which ensures we don't show a call chip
                // for User 1's call while User 2 is active (see b/328584859).
                // 2. ActiveNotificationsInteractor only emits notifications that are currently
                // present in the shade, which means we know we've already inflated the icon that we
                // might use for the call chip (see b/354930838).
                activeNotificationsInteractor.ongoingCallNotification.collect {
                    updateInfoFromNotifModel(it)
                }
            }
        } else {
            notifCollection.addCollectionListener(notifListener)
        }

        scope.launch {
            statusBarModeRepository.defaultDisplay.isInFullscreenMode.collect {
                isFullscreen = it
                updateChipClickListener()
                updateGestureListening()
            }
        }
    }

    /**
     * Sets the chip view that will contain ongoing call information.
     *
     * Should only be called from
     * [com.android.systemui.statusbar.phone.fragment.CollapsedStatusBarFragment].
     */
    fun setChipView(chipView: View) {
        tearDownChipView()
        this.chipView = chipView
        val backgroundView: ChipBackgroundContainer? =
            chipView.findViewById(R.id.ongoing_activity_chip_background)
        backgroundView?.maxHeightFetcher = {
            statusBarWindowControllerStore.defaultDisplay.statusBarHeight
        }
        if (hasOngoingCall()) {
            updateChip()
        }
    }

    /**
     * Returns true if there's an active ongoing call that should be displayed in a status bar chip.
     */
    fun hasOngoingCall(): Boolean {
        return callNotificationInfo?.isOngoing == true &&
            // When the user is in the phone app, don't show the chip.
            !uidObserver.isCallAppVisible
    }

    /** Creates the right [OngoingCallModel] based on the call state. */
    private fun getOngoingCallModel(): OngoingCallModel {
        if (hasOngoingCall()) {
            val currentInfo =
                callNotificationInfo
                    // This shouldn't happen, but protect against it in case
                    ?: return OngoingCallModel.NoCall
            logger.log(
                TAG,
                LogLevel.DEBUG,
                {
                    bool1 = Flags.statusBarCallChipNotificationIcon()
                    bool2 = currentInfo.notificationIconView != null
                },
                { "Creating OngoingCallModel.InCall. notifIconFlag=$bool1 hasIcon=$bool2" }
            )
            val icon =
                if (Flags.statusBarCallChipNotificationIcon()) {
                    currentInfo.notificationIconView
                } else {
                    null
                }
            return OngoingCallModel.InCall(
                startTimeMs = currentInfo.callStartTime,
                notificationIconView = icon,
                intent = currentInfo.intent,
            )
        } else {
            return OngoingCallModel.NoCall
        }
    }

    override fun addCallback(listener: OngoingCallListener) {
        synchronized(mListeners) {
            if (!mListeners.contains(listener)) {
                mListeners.add(listener)
            }
        }
    }

    override fun removeCallback(listener: OngoingCallListener) {
        synchronized(mListeners) { mListeners.remove(listener) }
    }

    private fun updateInfoFromNotifModel(notifModel: ActiveNotificationModel?) {
        if (notifModel == null) {
            logger.log(TAG, LogLevel.DEBUG, {}, { "NotifInteractorCallModel: null" })
            removeChip()
        } else if (notifModel.callType != CallType.Ongoing) {
            logger.log(
                TAG,
                LogLevel.ERROR,
                { str1 = notifModel.callType.name },
                { "Notification Interactor sent ActiveNotificationModel with callType=$str1" },
            )
            removeChip()
        } else {
            logger.log(
                TAG,
                LogLevel.DEBUG,
                {
                    str1 = notifModel.key
                    long1 = notifModel.whenTime
                    str1 = notifModel.callType.name
                    bool1 = notifModel.statusBarChipIconView != null
                },
                { "NotifInteractorCallModel: key=$str1 when=$long1 callType=$str2 hasIcon=$bool1" }
            )

            val newOngoingCallInfo =
                CallNotificationInfo(
                    notifModel.key,
                    notifModel.whenTime,
                    notifModel.statusBarChipIconView,
                    notifModel.contentIntent,
                    notifModel.uid,
                    isOngoing = true,
                    statusBarSwipedAway = callNotificationInfo?.statusBarSwipedAway ?: false
                )
            if (newOngoingCallInfo == callNotificationInfo) {
                return
            }
            callNotificationInfo = newOngoingCallInfo
            updateChip()
        }
    }

    private fun updateChip() {
        val currentCallNotificationInfo = callNotificationInfo ?: return

        val currentChipView = chipView
        val timeView = currentChipView?.getTimeView()

        if (currentChipView != null && timeView != null) {
            if (!Flags.statusBarScreenSharingChips()) {
                // If the new status bar screen sharing chips are enabled, then the display logic
                // for *all* status bar chips (both the call chip and the screen sharing chips) are
                // handled by CollapsedStatusBarViewBinder, *not* this class. We need to disable
                // this class from making any display changes because the new chips use the same
                // view as the old call chip.
                // TODO(b/332662551): We should move this whole controller class to recommended
                // architecture so that we don't need to awkwardly disable only some parts of this
                // class.
                if (currentCallNotificationInfo.hasValidStartTime()) {
                    timeView.setShouldHideText(false)
                    timeView.base =
                        currentCallNotificationInfo.callStartTime -
                            systemClock.currentTimeMillis() + systemClock.elapsedRealtime()
                    timeView.start()
                } else {
                    timeView.setShouldHideText(true)
                    timeView.stop()
                }
                updateChipClickListener()
            }

            // But, this class still needs to do the non-display logic regardless of the flag.
            uidObserver.registerWithUid(currentCallNotificationInfo.uid)
            if (!currentCallNotificationInfo.statusBarSwipedAway) {
                statusBarWindowControllerStore.defaultDisplay
                    .setOngoingProcessRequiresStatusBarVisible(true)
            }
            updateGestureListening()
            sendStateChangeEvent()
        } else {
            // If we failed to update the chip, don't store the call info. Then [hasOngoingCall]
            // will return false and we fall back to typical notification handling.
            callNotificationInfo = null
            logger.log(
                TAG,
                LogLevel.WARNING,
                {},
                { "Ongoing call chip view could not be found; Not displaying chip in status bar" },
            )
        }
    }

    private fun updateChipClickListener() {
        if (Flags.statusBarScreenSharingChips()) {
            return
        }

        if (callNotificationInfo == null) {
            return
        }
        val currentChipView = chipView
        val backgroundView =
            currentChipView?.findViewById<View>(R.id.ongoing_activity_chip_background)
        val intent = callNotificationInfo?.intent
        if (currentChipView != null && backgroundView != null && intent != null) {
            currentChipView.setOnClickListener {
                activityStarter.postStartActivityDismissingKeyguard(
                    intent,
                    ActivityTransitionAnimator.Controller.fromView(
                        backgroundView,
                        InteractionJankMonitor.CUJ_STATUS_BAR_APP_LAUNCH_FROM_CALL_CHIP,
                    )
                )
            }
        }
    }

    /** Returns true if the given [procState] represents a process that's visible to the user. */
    private fun isProcessVisibleToUser(procState: Int): Boolean {
        return procState <= ActivityManager.PROCESS_STATE_TOP
    }

    private fun updateGestureListening() {
        if (
            callNotificationInfo == null ||
                callNotificationInfo?.statusBarSwipedAway == true ||
                !isFullscreen
        ) {
            swipeStatusBarAwayGestureHandler.removeOnGestureDetectedCallback(TAG)
        } else {
            swipeStatusBarAwayGestureHandler.addOnGestureDetectedCallback(TAG) { _ ->
                onSwipeAwayGestureDetected()
            }
        }
    }

    private fun removeChip() {
        callNotificationInfo = null
        if (!Flags.statusBarScreenSharingChips()) {
            tearDownChipView()
        }
        statusBarWindowControllerStore.defaultDisplay.setOngoingProcessRequiresStatusBarVisible(
            false
        )
        swipeStatusBarAwayGestureHandler.removeOnGestureDetectedCallback(TAG)
        sendStateChangeEvent()
        uidObserver.unregister()
    }

    /** Tear down anything related to the chip view to prevent leaks. */
    @VisibleForTesting fun tearDownChipView() = chipView?.getTimeView()?.stop()

    private fun View.getTimeView(): ChipChronometer? {
        return this.findViewById(R.id.ongoing_activity_chip_time)
    }

    /**
     * If there's an active ongoing call, then we will force the status bar to always show, even if
     * the user is in immersive mode. However, we also want to give users the ability to swipe away
     * the status bar if they need to access the area under the status bar.
     *
     * This method updates the status bar window appropriately when the swipe away gesture is
     * detected.
     */
    private fun onSwipeAwayGestureDetected() {
        logger.log(TAG, LogLevel.DEBUG, {}, { "Swipe away gesture detected" })
        callNotificationInfo = callNotificationInfo?.copy(statusBarSwipedAway = true)
        statusBarWindowControllerStore.defaultDisplay.setOngoingProcessRequiresStatusBarVisible(
            false
        )
        swipeStatusBarAwayGestureHandler.removeOnGestureDetectedCallback(TAG)
    }

    private fun sendStateChangeEvent() {
        ongoingCallRepository.setOngoingCallState(getOngoingCallModel())
        mListeners.forEach { l -> l.onOngoingCallStateChanged(animate = true) }
    }

    private data class CallNotificationInfo(
        val key: String,
        val callStartTime: Long,
        /** The icon set as the [android.app.Notification.getSmallIcon] field. */
        val notificationIconView: StatusBarIconView?,
        val intent: PendingIntent?,
        val uid: Int,
        /** True if the call is currently ongoing (as opposed to incoming, screening, etc.). */
        val isOngoing: Boolean,
        /** True if the user has swiped away the status bar while in this phone call. */
        val statusBarSwipedAway: Boolean
    ) {
        /**
         * Returns true if the notification information has a valid call start time. See
         * b/192379214.
         */
        fun hasValidStartTime(): Boolean = callStartTime > 0
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("Active call notification: $callNotificationInfo")
        pw.println("Call app visible: ${uidObserver.isCallAppVisible}")
    }

    /**
     * Observer to tell us when the app that posted the ongoing call notification is visible so that
     * we don't show the call chip at the same time (since the timers could be out-of-sync).
     */
    inner class CallAppUidObserver : UidObserver() {
        /** True if the application managing the call is visible to the user. */
        var isCallAppVisible: Boolean = false
            private set

        /** The UID of the application managing the call. Null if there is no active call. */
        private var callAppUid: Int? = null

        /**
         * True if this observer is currently registered with the activity manager and false
         * otherwise.
         */
        private var isRegistered = false

        /** Register this observer with the activity manager and the given [uid]. */
        fun registerWithUid(uid: Int) {
            if (callAppUid == uid) {
                return
            }
            callAppUid = uid

            try {
                isCallAppVisible =
                    isProcessVisibleToUser(
                        iActivityManager.getUidProcessState(uid, context.opPackageName)
                    )
                logger.log(
                    TAG,
                    LogLevel.DEBUG,
                    { bool1 = isCallAppVisible },
                    { "On uid observer registration, isCallAppVisible=$bool1" },
                )
                if (isRegistered) {
                    return
                }
                iActivityManager.registerUidObserver(
                    uidObserver,
                    ActivityManager.UID_OBSERVER_PROCSTATE,
                    ActivityManager.PROCESS_STATE_UNKNOWN,
                    context.opPackageName
                )
                isRegistered = true
            } catch (se: SecurityException) {
                logger.log(
                    TAG,
                    LogLevel.ERROR,
                    {},
                    { "Security exception when trying to set up uid observer" },
                    se,
                )
            }
        }

        /** Unregister this observer with the activity manager. */
        fun unregister() {
            callAppUid = null
            isRegistered = false
            iActivityManager.unregisterUidObserver(uidObserver)
        }

        override fun onUidStateChanged(
            uid: Int,
            procState: Int,
            procStateSeq: Long,
            capability: Int
        ) {
            val currentCallAppUid = callAppUid ?: return
            if (uid != currentCallAppUid) {
                return
            }

            val oldIsCallAppVisible = isCallAppVisible
            isCallAppVisible = isProcessVisibleToUser(procState)
            if (oldIsCallAppVisible != isCallAppVisible) {
                logger.log(
                    TAG,
                    LogLevel.DEBUG,
                    { bool1 = isCallAppVisible },
                    { "#onUidStateChanged. isCallAppVisible=$bool1" },
                )
                // Animations may be run as a result of the call's state change, so ensure
                // the listener is notified on the main thread.
                mainExecutor.execute { sendStateChangeEvent() }
            }
        }
    }
}

private fun isCallNotification(entry: NotificationEntry): Boolean {
    return entry.sbn.notification.isStyle(Notification.CallStyle::class.java)
}

private const val TAG = OngoingCallRepository.TAG
