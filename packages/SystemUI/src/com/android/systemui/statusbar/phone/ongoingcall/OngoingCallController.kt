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
import android.app.PendingIntent
import android.app.UidObserver
import android.content.Context
import android.view.View
import androidx.annotation.VisibleForTesting
import com.android.app.tracing.coroutines.launchTraced as launch
import com.android.systemui.CoreStartable
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.chips.ui.view.ChipBackgroundContainer
import com.android.systemui.statusbar.chips.ui.view.ChipChronometer
import com.android.systemui.statusbar.data.repository.StatusBarModeRepositoryStore
import com.android.systemui.statusbar.gesture.SwipeStatusBarAwayGestureHandler
import com.android.systemui.statusbar.notification.domain.interactor.ActiveNotificationsInteractor
import com.android.systemui.statusbar.notification.promoted.shared.model.PromotedNotificationContentModel
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.statusbar.notification.shared.CallType
import com.android.systemui.statusbar.phone.ongoingcall.data.repository.OngoingCallRepository
import com.android.systemui.statusbar.phone.ongoingcall.shared.model.OngoingCallModel
import com.android.systemui.statusbar.policy.CallbackController
import com.android.systemui.statusbar.window.StatusBarWindowControllerStore
import java.io.PrintWriter
import java.util.concurrent.Executor
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

/**
 * A controller to handle the ongoing call chip in the collapsed status bar.
 *
 * @deprecated Use [OngoingCallInteractor] instead, which follows recommended architecture patterns
 */
@Deprecated("Use OngoingCallInteractor instead")
@SysUISingleton
class OngoingCallController
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val context: Context,
    private val ongoingCallRepository: OngoingCallRepository,
    private val activeNotificationsInteractor: ActiveNotificationsInteractor,
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

    override fun start() {
        if (StatusBarChipsModernization.isEnabled) return

        dumpManager.registerDumpable(this)

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

        scope.launch {
            statusBarModeRepository.defaultDisplay.isInFullscreenMode.collect {
                isFullscreen = it
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
        StatusBarChipsModernization.assertInLegacyMode()

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
        StatusBarChipsModernization.assertInLegacyMode()

        return callNotificationInfo?.isOngoing == true &&
            // When the user is in the phone app, don't show the chip.
            !uidObserver.isCallAppVisible
    }

    /** Creates the right [OngoingCallModel] based on the call state. */
    private fun getOngoingCallModel(): OngoingCallModel {
        StatusBarChipsModernization.assertInLegacyMode()

        if (hasOngoingCall()) {
            val currentInfo =
                callNotificationInfo
                    // This shouldn't happen, but protect against it in case
                    ?: return OngoingCallModel.NoCall
            logger.log(
                TAG,
                LogLevel.DEBUG,
                { bool1 = currentInfo.notificationIconView != null },
                { "Creating OngoingCallModel.InCall. hasIcon=$bool1" },
            )
            return OngoingCallModel.InCall(
                startTimeMs = currentInfo.callStartTime,
                notificationIconView = currentInfo.notificationIconView,
                intent = currentInfo.intent,
                notificationKey = currentInfo.key,
                appName = currentInfo.appName,
                promotedContent = currentInfo.promotedContent,
            )
        } else {
            return OngoingCallModel.NoCall
        }
    }

    override fun addCallback(listener: OngoingCallListener) {
        StatusBarChipsModernization.assertInLegacyMode()

        synchronized(mListeners) {
            if (!mListeners.contains(listener)) {
                mListeners.add(listener)
            }
        }
    }

    override fun removeCallback(listener: OngoingCallListener) {
        StatusBarChipsModernization.assertInLegacyMode()

        synchronized(mListeners) { mListeners.remove(listener) }
    }

    private fun updateInfoFromNotifModel(notifModel: ActiveNotificationModel?) {
        StatusBarChipsModernization.assertInLegacyMode()

        if (notifModel == null) {
            logger.log(TAG, LogLevel.DEBUG, {}, { "NotifInteractorCallModel: null" })
            removeChipInfo()
        } else if (notifModel.callType != CallType.Ongoing) {
            logger.log(
                TAG,
                LogLevel.ERROR,
                { str1 = notifModel.callType.name },
                { "Notification Interactor sent ActiveNotificationModel with callType=$str1" },
            )
            removeChipInfo()
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
                { "NotifInteractorCallModel: key=$str1 when=$long1 callType=$str2 hasIcon=$bool1" },
            )

            val newOngoingCallInfo =
                CallNotificationInfo(
                    notifModel.key,
                    notifModel.whenTime,
                    notifModel.statusBarChipIconView,
                    notifModel.contentIntent,
                    notifModel.uid,
                    notifModel.appName,
                    notifModel.promotedContent,
                    isOngoing = true,
                    statusBarSwipedAway = callNotificationInfo?.statusBarSwipedAway ?: false,
                )
            if (newOngoingCallInfo == callNotificationInfo) {
                return
            }
            callNotificationInfo = newOngoingCallInfo
            updateChip()
        }
    }

    private fun updateChip() {
        StatusBarChipsModernization.assertInLegacyMode()

        val currentCallNotificationInfo = callNotificationInfo ?: return

        val currentChipView = chipView
        val timeView = currentChipView?.getTimeView()

        if (currentChipView != null && timeView != null) {
            // Current behavior: Displaying the call chip is handled by HomeStatusBarViewBinder, but
            // this class is still responsible for the non-display logic.
            // Future behavior: if StatusBarChipsModernization flag is enabled, this class is
            // completely deprecated and does nothing.
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

    /** Returns true if the given [procState] represents a process that's visible to the user. */
    private fun isProcessVisibleToUser(procState: Int): Boolean {
        StatusBarChipsModernization.assertInLegacyMode()

        return procState <= ActivityManager.PROCESS_STATE_TOP
    }

    private fun updateGestureListening() {
        StatusBarChipsModernization.assertInLegacyMode()

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

    private fun removeChipInfo() {
        StatusBarChipsModernization.assertInLegacyMode()

        callNotificationInfo = null
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
        StatusBarChipsModernization.assertInLegacyMode()

        logger.log(TAG, LogLevel.DEBUG, {}, { "Swipe away gesture detected" })
        callNotificationInfo = callNotificationInfo?.copy(statusBarSwipedAway = true)
        statusBarWindowControllerStore.defaultDisplay.setOngoingProcessRequiresStatusBarVisible(
            false
        )
        swipeStatusBarAwayGestureHandler.removeOnGestureDetectedCallback(TAG)
    }

    private fun sendStateChangeEvent() {
        StatusBarChipsModernization.assertInLegacyMode()

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
        val appName: String,
        /**
         * If the call notification also meets promoted notification criteria, this field is filled
         * in with the content related to promotion. Otherwise null.
         */
        val promotedContent: PromotedNotificationContentModel?,
        /** True if the call is currently ongoing (as opposed to incoming, screening, etc.). */
        val isOngoing: Boolean,
        /** True if the user has swiped away the status bar while in this phone call. */
        val statusBarSwipedAway: Boolean,
    )

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("Active call notification: $callNotificationInfo")
        pw.println("Call app visible: ${uidObserver.isCallAppVisible}")
    }

    /**
     * Observer to tell us when the app that posted the ongoing call notification is visible so that
     * we don't show the call chip at the same time (since the timers could be out-of-sync).
     *
     * For a more recommended architecture implementation, see
     * [com.android.systemui.activity.data.repository.ActivityManagerRepository].
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
                    context.opPackageName,
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
            capability: Int,
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

private const val TAG = OngoingCallRepository.TAG
