/*
 * Copyright (C) 2024 The Android Open Source Project
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
package android.internal.statusbar

import android.app.Notification
import android.content.ComponentName
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.hardware.biometrics.IBiometricContextListener
import android.hardware.biometrics.IBiometricSysuiReceiver
import android.hardware.biometrics.PromptInfo
import android.hardware.fingerprint.IUdfpsRefreshRateRequestCallback
import android.media.INearbyMediaDevicesProvider
import android.media.MediaRoute2Info
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.UserHandle
import android.util.ArrayMap
import android.view.Display
import android.view.KeyEvent
import com.android.internal.logging.InstanceId
import com.android.internal.statusbar.IAddTileResultCallback
import com.android.internal.statusbar.ISessionListener
import com.android.internal.statusbar.IStatusBar
import com.android.internal.statusbar.IStatusBarService
import com.android.internal.statusbar.IUndoMediaTransferCallback
import com.android.internal.statusbar.LetterboxDetails
import com.android.internal.statusbar.NotificationVisibility
import com.android.internal.statusbar.RegisterStatusBarResult
import com.android.internal.statusbar.StatusBarIcon
import com.android.internal.view.AppearanceRegion
import org.mockito.kotlin.mock

class FakeStatusBarService : IStatusBarService.Stub() {

    var registeredStatusBar: IStatusBar? = null
        private set

    var statusBarIcons =
        ArrayMap<String, StatusBarIcon>().also {
            it["slot1"] = mock<StatusBarIcon>()
            it["slot2"] = mock<StatusBarIcon>()
        }
    var disabledFlags1 = 1234567
    var appearance = 123
    var appearanceRegions =
        arrayOf(
            AppearanceRegion(
                /* appearance = */ 123,
                /* bounds = */ Rect(/* left= */ 4, /* top= */ 3, /* right= */ 2, /* bottom= */ 1),
            ),
            AppearanceRegion(
                /* appearance = */ 345,
                /* bounds = */ Rect(/* left= */ 1, /* top= */ 2, /* right= */ 3, /* bottom= */ 4),
            ),
        )
    var imeWindowVis = 987
    var imeBackDisposition = 654
    var showImeSwitcher = true
    var disabledFlags2 = 7654321
    var navbarColorManagedByIme = true
    var behavior = 234
    var requestedVisibleTypes = 345
    var packageName = "fake.bar.ser.vice"
    var transientBarTypes = 0
    var letterboxDetails =
        arrayOf(
            LetterboxDetails(
                /* letterboxInnerBounds = */ Rect(
                    /* left= */ 5,
                    /* top= */ 6,
                    /* right= */ 7,
                    /* bottom= */ 8,
                ),
                /* letterboxFullBounds = */ Rect(
                    /* left= */ 1,
                    /* top= */ 2,
                    /* right= */ 3,
                    /* bottom= */ 4,
                ),
                /* appAppearance = */ 123,
            )
        )

    var statusBarIconsSecondaryDisplay =
        ArrayMap<String, StatusBarIcon>().also {
            it["slot1"] = mock<StatusBarIcon>()
            it["slot2"] = mock<StatusBarIcon>()
        }
    var disabledFlags1SecondaryDisplay = 12345678
    var appearanceSecondaryDisplay = 1234
    var appearanceRegionsSecondaryDisplay =
        arrayOf(
            AppearanceRegion(
                /* appearance = */ 123,
                /* bounds = */ Rect(/* left= */ 4, /* top= */ 3, /* right= */ 2, /* bottom= */ 1),
            ),
            AppearanceRegion(
                /* appearance = */ 345,
                /* bounds = */ Rect(/* left= */ 1, /* top= */ 2, /* right= */ 3, /* bottom= */ 4),
            ),
        )
    var imeWindowVisSecondaryDisplay = 9876
    var imeBackDispositionSecondaryDisplay = 654
    var showImeSwitcherSecondaryDisplay = true
    var disabledFlags2SecondaryDisplay = 87654321
    var navbarColorManagedByImeSecondaryDisplay = true
    var behaviorSecondaryDisplay = 234
    var requestedVisibleTypesSecondaryDisplay = 345
    var packageNameSecondaryDisplay = "fake.bar.ser.vice"
    var transientBarTypesSecondaryDisplay = 0
    var letterboxDetailsSecondaryDisplay =
        arrayOf(
            LetterboxDetails(
                /* letterboxInnerBounds = */ Rect(
                    /* left= */ 5,
                    /* top= */ 6,
                    /* right= */ 7,
                    /* bottom= */ 8,
                ),
                /* letterboxFullBounds = */ Rect(
                    /* left= */ 1,
                    /* top= */ 2,
                    /* right= */ 3,
                    /* bottom= */ 4,
                ),
                /* appAppearance = */ 123,
            )
        )

    private val defaultRegisterStatusBarResult
        get() =
            RegisterStatusBarResult(
                statusBarIcons,
                disabledFlags1,
                appearance,
                appearanceRegions,
                imeWindowVis,
                imeBackDisposition,
                showImeSwitcher,
                disabledFlags2,
                navbarColorManagedByIme,
                behavior,
                requestedVisibleTypes,
                packageName,
                transientBarTypes,
                letterboxDetails,
            )

    private val registerStatusBarResultSecondaryDisplay
        get() =
            RegisterStatusBarResult(
                statusBarIconsSecondaryDisplay,
                disabledFlags1SecondaryDisplay,
                appearanceSecondaryDisplay,
                appearanceRegionsSecondaryDisplay,
                imeWindowVisSecondaryDisplay,
                imeBackDispositionSecondaryDisplay,
                showImeSwitcherSecondaryDisplay,
                disabledFlags2SecondaryDisplay,
                navbarColorManagedByImeSecondaryDisplay,
                behaviorSecondaryDisplay,
                requestedVisibleTypesSecondaryDisplay,
                packageNameSecondaryDisplay,
                transientBarTypesSecondaryDisplay,
                letterboxDetailsSecondaryDisplay,
            )

    override fun expandNotificationsPanel() {}

    override fun collapsePanels() {}

    override fun togglePanel() {}

    override fun disable(what: Int, token: IBinder, pkg: String) {
        disableForUser(what, token, pkg, userId = 0)
    }

    override fun disableForUser(what: Int, token: IBinder, pkg: String, userId: Int) {}

    override fun disable2(what: Int, token: IBinder, pkg: String) {
        disable2ForUser(what, token, pkg, userId = 0)
    }

    override fun disable2ForUser(what: Int, token: IBinder, pkg: String, userId: Int) {}

    override fun getDisableFlags(token: IBinder, userId: Int): IntArray {
        return intArrayOf(disabledFlags1, disabledFlags2)
    }

    override fun setIcon(
        slot: String,
        iconPackage: String,
        iconId: Int,
        iconLevel: Int,
        contentDescription: String,
    ) {}

    override fun setIconVisibility(slot: String, visible: Boolean) {}

    override fun removeIcon(slot: String) {}

    override fun setImeWindowStatus(
        displayId: Int,
        vis: Int,
        backDisposition: Int,
        showImeSwitcher: Boolean,
    ) {}

    override fun expandSettingsPanel(subPanel: String) {}

    override fun registerStatusBar(callbacks: IStatusBar): RegisterStatusBarResult {
        registeredStatusBar = callbacks
        return defaultRegisterStatusBarResult
    }

    override fun registerStatusBarForAllDisplays(
        callbacks: IStatusBar
    ): Map<String, RegisterStatusBarResult> {
        registeredStatusBar = callbacks
        return mapOf(
            DEFAULT_DISPLAY_ID.toString() to defaultRegisterStatusBarResult,
            SECONDARY_DISPLAY_ID.toString() to registerStatusBarResultSecondaryDisplay,
        )
    }

    override fun onPanelRevealed(clearNotificationEffects: Boolean, numItems: Int) {}

    override fun onPanelHidden() {}

    override fun clearNotificationEffects() {}

    override fun onNotificationClick(key: String, nv: NotificationVisibility) {}

    override fun onNotificationActionClick(
        key: String,
        actionIndex: Int,
        action: Notification.Action,
        nv: NotificationVisibility,
        generatedByAssistant: Boolean,
    ) {}

    override fun onNotificationError(
        pkg: String,
        tag: String,
        id: Int,
        uid: Int,
        initialPid: Int,
        message: String,
        userId: Int,
    ) {}

    override fun onClearAllNotifications(userId: Int) {}

    override fun onNotificationClear(
        pkg: String,
        userId: Int,
        key: String,
        dismissalSurface: Int,
        dismissalSentiment: Int,
        nv: NotificationVisibility,
    ) {}

    override fun onNotificationVisibilityChanged(
        newlyVisibleKeys: Array<NotificationVisibility>,
        noLongerVisibleKeys: Array<NotificationVisibility>,
    ) {}

    override fun onNotificationExpansionChanged(
        key: String,
        userAction: Boolean,
        expanded: Boolean,
        notificationLocation: Int,
    ) {}

    override fun onNotificationDirectReplied(key: String) {}

    override fun onNotificationSmartSuggestionsAdded(
        key: String,
        smartReplyCount: Int,
        smartActionCount: Int,
        generatedByAssistant: Boolean,
        editBeforeSending: Boolean,
    ) {}

    override fun onNotificationSmartReplySent(
        key: String,
        replyIndex: Int,
        reply: CharSequence,
        notificationLocation: Int,
        modifiedBeforeSending: Boolean,
    ) {}

    override fun onNotificationSettingsViewed(key: String) {}

    override fun onNotificationBubbleChanged(key: String, isBubble: Boolean, flags: Int) {}

    override fun onBubbleMetadataFlagChanged(key: String, flags: Int) {}

    override fun hideCurrentInputMethodForBubbles(displayId: Int) {}

    override fun grantInlineReplyUriPermission(
        key: String,
        uri: Uri,
        user: UserHandle,
        packageName: String,
    ) {}

    override fun clearInlineReplyUriPermissions(key: String) {}

    override fun onNotificationFeedbackReceived(key: String, feedback: Bundle) {}

    override fun onGlobalActionsShown() {}

    override fun onGlobalActionsHidden() {}

    override fun shutdown() {}

    override fun reboot(safeMode: Boolean) {}

    override fun restart() {}

    override fun addTile(tile: ComponentName) {}

    override fun remTile(tile: ComponentName) {}

    override fun clickTile(tile: ComponentName) {}

    override fun handleSystemKey(key: KeyEvent) {}

    override fun getLastSystemKey(): Int {
        return -1
    }

    override fun showPinningEnterExitToast(entering: Boolean) {}

    override fun showPinningEscapeToast() {}

    override fun showAuthenticationDialog(
        promptInfo: PromptInfo,
        sysuiReceiver: IBiometricSysuiReceiver,
        sensorIds: IntArray,
        credentialAllowed: Boolean,
        requireConfirmation: Boolean,
        userId: Int,
        operationId: Long,
        opPackageName: String,
        requestId: Long,
    ) {}

    override fun onBiometricAuthenticated(modality: Int) {}

    override fun onBiometricHelp(modality: Int, message: String) {}

    override fun onBiometricError(modality: Int, error: Int, vendorCode: Int) {}

    override fun hideAuthenticationDialog(requestId: Long) {}

    override fun setBiometicContextListener(listener: IBiometricContextListener) {}

    override fun setUdfpsRefreshRateCallback(callback: IUdfpsRefreshRateRequestCallback) {}

    override fun showInattentiveSleepWarning() {}

    override fun dismissInattentiveSleepWarning(animated: Boolean) {}

    override fun startTracing() {}

    override fun stopTracing() {}

    override fun isTracing(): Boolean {
        return false
    }

    override fun suppressAmbientDisplay(suppress: Boolean) {}

    override fun requestTileServiceListeningState(componentName: ComponentName, userId: Int) {}

    override fun requestAddTile(
        componentName: ComponentName,
        label: CharSequence,
        icon: Icon,
        userId: Int,
        callback: IAddTileResultCallback,
    ) {}

    override fun cancelRequestAddTile(packageName: String) {}

    override fun setNavBarMode(navBarMode: Int) {}

    override fun getNavBarMode(): Int {
        return -1
    }

    override fun registerSessionListener(sessionFlags: Int, listener: ISessionListener) {}

    override fun unregisterSessionListener(sessionFlags: Int, listener: ISessionListener) {}

    override fun onSessionStarted(sessionType: Int, instanceId: InstanceId) {}

    override fun onSessionEnded(sessionType: Int, instanceId: InstanceId) {}

    override fun updateMediaTapToTransferSenderDisplay(
        displayState: Int,
        routeInfo: MediaRoute2Info,
        undoCallback: IUndoMediaTransferCallback,
    ) {}

    override fun updateMediaTapToTransferReceiverDisplay(
        displayState: Int,
        routeInfo: MediaRoute2Info,
        appIcon: Icon,
        appName: CharSequence,
    ) {}

    override fun registerNearbyMediaDevicesProvider(provider: INearbyMediaDevicesProvider) {}

    override fun unregisterNearbyMediaDevicesProvider(provider: INearbyMediaDevicesProvider) {}

    override fun showRearDisplayDialog(currentBaseState: Int) {}

    companion object {
        const val DEFAULT_DISPLAY_ID = Display.DEFAULT_DISPLAY
        const val SECONDARY_DISPLAY_ID = 2
    }
}
