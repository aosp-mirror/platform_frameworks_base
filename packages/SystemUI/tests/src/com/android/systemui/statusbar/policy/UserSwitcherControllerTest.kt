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

package com.android.systemui.statusbar.policy

import android.app.IActivityTaskManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.UserInfo
import android.graphics.Bitmap
import android.hardware.face.FaceManager
import android.hardware.fingerprint.FingerprintManager
import android.os.Handler
import android.os.UserHandle
import android.os.UserManager
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.logging.testing.UiEventLoggerFake
import com.android.internal.util.UserIcons
import com.android.systemui.GuestResumeSessionReceiver
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.qs.QSUserSwitcherEvent
import com.android.systemui.settings.UserTracker
import com.android.systemui.telephony.TelephonyListenerManager
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.any
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@SmallTest
class UserSwitcherControllerTest : SysuiTestCase() {
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var deviceProvisionedController: DeviceProvisionedController
    @Mock private lateinit var devicePolicyManager: DevicePolicyManager
    @Mock private lateinit var handler: Handler
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var userManager: UserManager
    @Mock private lateinit var activityStarter: ActivityStarter
    @Mock private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock private lateinit var activityTaskManager: IActivityTaskManager
    @Mock private lateinit var userDetailAdapter: UserSwitcherController.UserDetailAdapter
    @Mock private lateinit var telephonyListenerManager: TelephonyListenerManager
    @Mock private lateinit var secureSettings: SecureSettings
    @Mock private lateinit var falsingManager: FalsingManager
    @Mock private lateinit var dumpManager: DumpManager
    private lateinit var testableLooper: TestableLooper
    private lateinit var uiBgExecutor: FakeExecutor
    private lateinit var uiEventLogger: UiEventLoggerFake
    private lateinit var userSwitcherController: UserSwitcherController
    private lateinit var picture: Bitmap
    private val ownerId = UserHandle.USER_SYSTEM
    private val ownerInfo = UserInfo(ownerId, "Owner", null,
            UserInfo.FLAG_ADMIN or UserInfo.FLAG_FULL or UserInfo.FLAG_INITIALIZED or
                    UserInfo.FLAG_PRIMARY or UserInfo.FLAG_SYSTEM,
            UserManager.USER_TYPE_FULL_SYSTEM)
    private val guestId = 1234
    private val guestInfo = UserInfo(guestId, "Guest", null,
            UserInfo.FLAG_FULL or UserInfo.FLAG_GUEST, UserManager.USER_TYPE_FULL_GUEST)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        uiBgExecutor = FakeExecutor(FakeSystemClock())
        uiEventLogger = UiEventLoggerFake()

        context.orCreateTestableResources.addOverride(
                com.android.internal.R.bool.config_guestUserAutoCreated, false)

        mContext.addMockSystemService(Context.FACE_SERVICE, mock(FaceManager::class.java))
        mContext.addMockSystemService(Context.FINGERPRINT_SERVICE,
                mock(FingerprintManager::class.java))

        `when`(userManager.canAddMoreUsers()).thenReturn(true)

        userSwitcherController = UserSwitcherController(
                context,
                userManager,
                userTracker,
                keyguardStateController,
                deviceProvisionedController,
                devicePolicyManager,
                handler,
                activityStarter,
                broadcastDispatcher,
                uiEventLogger,
                falsingManager,
                telephonyListenerManager,
                activityTaskManager,
                userDetailAdapter,
                secureSettings,
                uiBgExecutor,
                dumpManager)
        userSwitcherController.mPauseRefreshUsers = true

        picture = UserIcons.convertToBitmap(context.getDrawable(R.drawable.ic_avatar_user))
    }

    @Test
    fun testAddGuest_okButtonPressed_isLogged() {
        val emptyGuestUserRecord = UserSwitcherController.UserRecord(
                null,
                null,
                true /* guest */,
                false /* current */,
                false /* isAddUser */,
                false /* isRestricted */,
                true /* isSwitchToEnabled */)
        `when`(userTracker.userId).thenReturn(ownerId)
        `when`(userTracker.userInfo).thenReturn(ownerInfo)

        `when`(userManager.createGuest(any(), anyString())).thenReturn(guestInfo)

        userSwitcherController.onUserListItemClicked(emptyGuestUserRecord)
        testableLooper.processAllMessages()
        assertEquals(1, uiEventLogger.numLogs())
        assertEquals(QSUserSwitcherEvent.QS_USER_GUEST_ADD.id, uiEventLogger.eventId(0))
    }

    @Test
    fun testRemoveGuest_removeButtonPressed_isLogged() {
        val currentGuestUserRecord = UserSwitcherController.UserRecord(
                guestInfo,
                picture,
                true /* guest */,
                true /* current */,
                false /* isAddUser */,
                false /* isRestricted */,
                true /* isSwitchToEnabled */)
        `when`(userTracker.userId).thenReturn(guestInfo.id)
        `when`(userTracker.userInfo).thenReturn(guestInfo)

        userSwitcherController.onUserListItemClicked(currentGuestUserRecord)
        assertNotNull(userSwitcherController.mExitGuestDialog)
        userSwitcherController.mExitGuestDialog
                .getButton(DialogInterface.BUTTON_POSITIVE).performClick()
        testableLooper.processAllMessages()
        assertEquals(1, uiEventLogger.numLogs())
        assertEquals(QSUserSwitcherEvent.QS_USER_GUEST_REMOVE.id, uiEventLogger.eventId(0))
    }

    @Test
    fun testRemoveGuest_cancelButtonPressed_isNotLogged() {
        val currentGuestUserRecord = UserSwitcherController.UserRecord(
                guestInfo,
                picture,
                true /* guest */,
                true /* current */,
                false /* isAddUser */,
                false /* isRestricted */,
                true /* isSwitchToEnabled */)
        `when`(userTracker.userId).thenReturn(guestId)
        `when`(userTracker.userInfo).thenReturn(guestInfo)

        userSwitcherController.onUserListItemClicked(currentGuestUserRecord)
        assertNotNull(userSwitcherController.mExitGuestDialog)
        userSwitcherController.mExitGuestDialog
                .getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
        testableLooper.processAllMessages()
        assertEquals(0, uiEventLogger.numLogs())
    }

    @Test
    fun testWipeGuest_startOverButtonPressed_isLogged() {
        val currentGuestUserRecord = UserSwitcherController.UserRecord(
                guestInfo,
                picture,
                true /* guest */,
                false /* current */,
                false /* isAddUser */,
                false /* isRestricted */,
                true /* isSwitchToEnabled */)
        `when`(userTracker.userId).thenReturn(guestId)
        `when`(userTracker.userInfo).thenReturn(guestInfo)

        // Simulate that guest user has already logged in
        `when`(secureSettings.getIntForUser(
                eq(GuestResumeSessionReceiver.SETTING_GUEST_HAS_LOGGED_IN), anyInt(), anyInt()))
                .thenReturn(1)

        userSwitcherController.onUserListItemClicked(currentGuestUserRecord)

        // Simulate a user switch event
        val intent = Intent(Intent.ACTION_USER_SWITCHED).putExtra(Intent.EXTRA_USER_HANDLE, guestId)

        assertNotNull(userSwitcherController.mGuestResumeSessionReceiver)
        userSwitcherController.mGuestResumeSessionReceiver.onReceive(context, intent)

        assertNotNull(userSwitcherController.mGuestResumeSessionReceiver.mNewSessionDialog)
        userSwitcherController.mGuestResumeSessionReceiver.mNewSessionDialog
                .getButton(GuestResumeSessionReceiver.ResetSessionDialog.BUTTON_WIPE).performClick()
        testableLooper.processAllMessages()
        assertEquals(1, uiEventLogger.numLogs())
        assertEquals(QSUserSwitcherEvent.QS_USER_GUEST_WIPE.id, uiEventLogger.eventId(0))
    }

    @Test
    fun testWipeGuest_continueButtonPressed_isLogged() {
        val currentGuestUserRecord = UserSwitcherController.UserRecord(
                guestInfo,
                picture,
                true /* guest */,
                false /* current */,
                false /* isAddUser */,
                false /* isRestricted */,
                true /* isSwitchToEnabled */)
        `when`(userTracker.userId).thenReturn(guestId)
        `when`(userTracker.userInfo).thenReturn(guestInfo)

        // Simulate that guest user has already logged in
        `when`(secureSettings.getIntForUser(
                eq(GuestResumeSessionReceiver.SETTING_GUEST_HAS_LOGGED_IN), anyInt(), anyInt()))
                .thenReturn(1)

        userSwitcherController.onUserListItemClicked(currentGuestUserRecord)

        // Simulate a user switch event
        val intent = Intent(Intent.ACTION_USER_SWITCHED).putExtra(Intent.EXTRA_USER_HANDLE, guestId)

        assertNotNull(userSwitcherController.mGuestResumeSessionReceiver)
        userSwitcherController.mGuestResumeSessionReceiver.onReceive(context, intent)

        assertNotNull(userSwitcherController.mGuestResumeSessionReceiver.mNewSessionDialog)
        userSwitcherController.mGuestResumeSessionReceiver.mNewSessionDialog
                .getButton(GuestResumeSessionReceiver.ResetSessionDialog.BUTTON_DONTWIPE)
                .performClick()
        testableLooper.processAllMessages()
        assertEquals(1, uiEventLogger.numLogs())
        assertEquals(QSUserSwitcherEvent.QS_USER_GUEST_CONTINUE.id, uiEventLogger.eventId(0))
    }
}
