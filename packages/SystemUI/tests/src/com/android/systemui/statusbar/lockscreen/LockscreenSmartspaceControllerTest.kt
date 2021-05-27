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

package com.android.systemui.statusbar.lockscreen

import android.app.smartspace.SmartspaceManager
import android.app.smartspace.SmartspaceSession
import android.app.smartspace.SmartspaceSession.OnTargetsAvailableListener
import android.app.smartspace.SmartspaceTarget
import android.content.ComponentName
import android.content.ContentResolver
import android.content.pm.UserInfo
import android.database.ContentObserver
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.UserHandle
import android.provider.Settings
import android.view.View
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.BcSmartspaceDataPlugin
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceTargetListener
import com.android.systemui.plugins.BcSmartspaceDataPlugin.SmartspaceView
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.plugins.statusbar.StatusBarStateController.StateListener
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.FeatureFlags
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.ConfigurationController.ConfigurationListener
import com.android.systemui.util.concurrency.FakeExecution
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.settings.SecureSettings
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.util.Optional

@SmallTest
class LockscreenSmartspaceControllerTest : SysuiTestCase() {
    @Mock
    private lateinit var featureFlags: FeatureFlags
    @Mock
    private lateinit var smartspaceManager: SmartspaceManager
    @Mock
    private lateinit var smartspaceSession: SmartspaceSession
    @Mock
    private lateinit var activityStarter: ActivityStarter
    @Mock
    private lateinit var falsingManager: FalsingManager
    @Mock
    private lateinit var secureSettings: SecureSettings
    @Mock
    private lateinit var userTracker: UserTracker
    @Mock
    private lateinit var contentResolver: ContentResolver
    @Mock
    private lateinit var configurationController: ConfigurationController
    @Mock
    private lateinit var statusBarStateController: StatusBarStateController
    @Mock
    private lateinit var handler: Handler

    @Mock
    private lateinit var plugin: BcSmartspaceDataPlugin
    @Mock
    private lateinit var controllerListener: SmartspaceTargetListener

    @Captor
    private lateinit var sessionListenerCaptor: ArgumentCaptor<OnTargetsAvailableListener>
    @Captor
    private lateinit var userTrackerCaptor: ArgumentCaptor<UserTracker.Callback>
    @Captor
    private lateinit var settingsObserverCaptor: ArgumentCaptor<ContentObserver>
    @Captor
    private lateinit var configChangeListenerCaptor: ArgumentCaptor<ConfigurationListener>
    @Captor
    private lateinit var statusBarStateListenerCaptor: ArgumentCaptor<StateListener>

    private lateinit var sessionListener: OnTargetsAvailableListener
    private lateinit var userListener: UserTracker.Callback
    private lateinit var settingsObserver: ContentObserver
    private lateinit var configChangeListener: ConfigurationListener
    private lateinit var statusBarStateListener: StateListener

    private val clock = FakeSystemClock()
    private val executor = FakeExecutor(clock)
    private val execution = FakeExecution()
    private val fakeParent = FrameLayout(context)
    private val fakePrivateLockscreenSettingUri = Uri.Builder().appendPath("test").build()

    private val userHandlePrimary: UserHandle = UserHandle(0)
    private val userHandleManaged: UserHandle = UserHandle(2)
    private val userHandleSecondary: UserHandle = UserHandle(3)

    private val userList = listOf(
            mockUserInfo(userHandlePrimary, isManagedProfile = false),
            mockUserInfo(userHandleManaged, isManagedProfile = true),
            mockUserInfo(userHandleSecondary, isManagedProfile = false)
    )

    private lateinit var controller: LockscreenSmartspaceController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        `when`(featureFlags.isSmartspaceEnabled).thenReturn(true)

        `when`(secureSettings.getUriFor(PRIVATE_LOCKSCREEN_SETTING))
                .thenReturn(fakePrivateLockscreenSettingUri)
        `when`(smartspaceManager.createSmartspaceSession(any())).thenReturn(smartspaceSession)
        `when`(plugin.getView(any())).thenReturn(fakeSmartspaceView)
        `when`(userTracker.userProfiles).thenReturn(userList)
        `when`(statusBarStateController.dozeAmount).thenReturn(0.5f)

        setActiveUser(userHandlePrimary)
        setAllowPrivateNotifications(userHandlePrimary, true)
        setAllowPrivateNotifications(userHandleManaged, true)
        setAllowPrivateNotifications(userHandleSecondary, true)

        controller = LockscreenSmartspaceController(
                context,
                featureFlags,
                smartspaceManager,
                activityStarter,
                falsingManager,
                secureSettings,
                userTracker,
                contentResolver,
                configurationController,
                statusBarStateController,
                execution,
                executor,
                handler,
                Optional.of(plugin)
                )
    }

    @Test(expected = RuntimeException::class)
    fun testThrowsIfFlagIsDisabled() {
        // GIVEN the feature flag is disabled
        `when`(featureFlags.isSmartspaceEnabled).thenReturn(false)

        // WHEN we try to build the view
        controller.buildAndConnectView(fakeParent)

        // THEN an exception is thrown
    }

    @Test
    fun testListenersAreRegistered() {
        // GIVEN a listener is added after a session is created
        connectSession()

        // WHEN a listener is registered
        controller.addListener(controllerListener)

        // THEN the listener is registered to the underlying plugin
        verify(plugin).registerListener(controllerListener)
    }

    @Test
    fun testEarlyRegisteredListenersAreAttachedAfterConnected() {
        // GIVEN a listener that is registered before the session is created
        controller.addListener(controllerListener)

        // WHEN the session is created
        connectSession()

        // THEN the listener is subsequently registered
        verify(plugin).registerListener(controllerListener)
    }

    @Test
    fun testEmptyListIsEmittedAfterDisconnect() {
        // GIVEN a registered listener on an active session
        connectSession()
        clearInvocations(plugin)

        // WHEN the session is closed
        controller.disconnect()

        // THEN the listener receives an empty list of targets
        verify(plugin).onTargetsAvailable(emptyList())
    }

    @Test
    fun testUserChangeReloadsSmartspace() {
        // GIVEN a connected smartspace session
        connectSession()

        // WHEN the active user changes
        userListener.onUserChanged(-1, context)

        // THEN we request a new smartspace update
        verify(smartspaceSession).requestSmartspaceUpdate()
    }

    @Test
    fun testSettingsChangeReloadsSmartspace() {
        // GIVEN a connected smartspace session
        connectSession()

        // WHEN the lockscreen privacy setting changes
        settingsObserver.onChange(true, null)

        // THEN we request a new smartspace update
        verify(smartspaceSession).requestSmartspaceUpdate()
    }

    @Test
    fun testThemeChangeUpdatesTextColor() {
        // GIVEN a connected smartspace session
        connectSession()

        // WHEN the theme changes
        configChangeListener.onThemeChanged()

        // We update the new text color to match the wallpaper color
        verify(fakeSmartspaceView).setPrimaryTextColor(anyInt())
    }

    @Test
    fun testDozeAmountChangeUpdatesView() {
        // GIVEN a connected smartspace session
        connectSession()

        // WHEN the doze amount changes
        statusBarStateListener.onDozeAmountChanged(0.1f, 0.7f)

        // We pass that along to the view
        verify(fakeSmartspaceView).setDozeAmount(0.7f)
    }

    @Test
    fun testSensitiveTargetsAreNotFilteredIfAllowed() {
        // GIVEN the active and managed users allow sensitive content
        connectSession()

        // WHEN we receive a list of targets
        val targets = listOf(
                makeTarget(1, userHandlePrimary, isSensitive = true),
                makeTarget(2, userHandleManaged, isSensitive = true),
                makeTarget(3, userHandlePrimary, isSensitive = true)
        )
        sessionListener.onTargetsAvailable(targets)

        // THEN all sensitive content is still shown
        verify(plugin).onTargetsAvailable(eq(targets))
    }

    @Test
    fun testNonSensitiveTargetsAreNeverFiltered() {
        // GIVEN the active user doesn't allow sensitive lockscreen content
        setAllowPrivateNotifications(userHandlePrimary, false)
        connectSession()

        // WHEN we receive a list of targets
        val targets = listOf(
                makeTarget(1, userHandlePrimary),
                makeTarget(2, userHandlePrimary),
                makeTarget(3, userHandlePrimary)
        )
        sessionListener.onTargetsAvailable(targets)

        // THEN all non-sensitive content is still shown
        verify(plugin).onTargetsAvailable(eq(targets))
    }

    @Test
    fun testSensitiveTargetsAreFilteredOutForAppropriateUsers() {
        // GIVEN the active and managed users don't allow sensitive lockscreen content
        setAllowPrivateNotifications(userHandlePrimary, false)
        setAllowPrivateNotifications(userHandleManaged, false)
        connectSession()

        // WHEN we receive a list of targets
        val targets = listOf(
                makeTarget(0, userHandlePrimary),
                makeTarget(1, userHandlePrimary, isSensitive = true),
                makeTarget(2, userHandleManaged, isSensitive = true),
                makeTarget(3, userHandleManaged),
                makeTarget(4, userHandlePrimary, isSensitive = true),
                makeTarget(5, userHandlePrimary),
                makeTarget(6, userHandleSecondary, isSensitive = true)
        )
        sessionListener.onTargetsAvailable(targets)

        // THEN only non-sensitive content from those accounts is shown
        verify(plugin).onTargetsAvailable(eq(listOf(
                targets[0],
                targets[3],
                targets[5]
        )))
    }

    @Test
    fun testSettingsAreReloaded() {
        // GIVEN a connected session where the privacy settings later flip to false
        connectSession()
        setAllowPrivateNotifications(userHandlePrimary, false)
        setAllowPrivateNotifications(userHandleManaged, false)
        settingsObserver.onChange(true, fakePrivateLockscreenSettingUri)

        // WHEN we receive a new list of targets
        val targets = listOf(
                makeTarget(1, userHandlePrimary, isSensitive = true),
                makeTarget(2, userHandleManaged, isSensitive = true),
                makeTarget(4, userHandlePrimary, isSensitive = true)
        )
        sessionListener.onTargetsAvailable(targets)

        // THEN we filter based on the new settings values
        verify(plugin).onTargetsAvailable(emptyList())
    }

    @Test
    fun testRecognizeSwitchToSecondaryUser() {
        // GIVEN an inactive secondary user that doesn't allow sensitive content
        setAllowPrivateNotifications(userHandleSecondary, false)
        connectSession()

        // WHEN the secondary user becomes the active user
        setActiveUser(userHandleSecondary)
        userListener.onUserChanged(userHandleSecondary.identifier, context)

        // WHEN we receive a new list of targets
        val targets = listOf(
                makeTarget(0, userHandlePrimary),
                makeTarget(1, userHandleSecondary),
                makeTarget(2, userHandleSecondary, isSensitive = true),
                makeTarget(3, userHandleManaged),
                makeTarget(4, userHandleSecondary),
                makeTarget(5, userHandleManaged),
                makeTarget(6, userHandlePrimary)
        )
        sessionListener.onTargetsAvailable(targets)

        // THEN only non-sensitive content from the secondary user is shown
        verify(plugin).onTargetsAvailable(listOf(
                targets[1],
                targets[4]
        ))
    }

    @Test
    fun testUnregisterListenersOnCleanup() {
        // GIVEN a connected session
        connectSession()

        // WHEN we are told to cleanup
        controller.disconnect()

        // THEN we disconnect from the session and unregister any listeners
        verify(smartspaceSession).removeOnTargetsAvailableListener(sessionListener)
        verify(smartspaceSession).close()
        verify(userTracker).removeCallback(userListener)
        verify(contentResolver).unregisterContentObserver(settingsObserver)
        verify(configurationController).removeCallback(configChangeListener)
        verify(statusBarStateController).removeCallback(statusBarStateListener)
    }

    @Test
    fun testBuildViewIsIdempotent() {
        // GIVEN a connected session
        connectSession()
        clearInvocations(plugin)

        // WHEN we disconnect and then reconnect
        controller.disconnect()
        controller.buildAndConnectView(fakeParent)

        // THEN the view is not rebuilt
        verify(plugin, never()).getView(any())
        assertEquals(fakeSmartspaceView, controller.view)
    }

    @Test
    fun testDoubleConnectIsIgnored() {
        // GIVEN a connected session
        connectSession()
        clearInvocations(smartspaceManager)
        clearInvocations(plugin)

        // WHEN we're asked to connect a second time and add to a parent
        val view = controller.buildAndConnectView(fakeParent)
        fakeParent.addView(view)

        // THEN the existing view and session are reused
        verify(smartspaceManager, never()).createSmartspaceSession(any())
        verify(plugin, never()).getView(any())
        assertEquals(fakeSmartspaceView, controller.view)
    }

    private fun connectSession() {
        controller.buildAndConnectView(fakeParent)

        verify(smartspaceSession)
                .addOnTargetsAvailableListener(any(), capture(sessionListenerCaptor))
        sessionListener = sessionListenerCaptor.value

        verify(userTracker).addCallback(capture(userTrackerCaptor), any())
        userListener = userTrackerCaptor.value

        verify(contentResolver).registerContentObserver(
                eq(fakePrivateLockscreenSettingUri),
                eq(true),
                capture(settingsObserverCaptor),
                eq(UserHandle.USER_ALL))
        settingsObserver = settingsObserverCaptor.value

        verify(configurationController).addCallback(configChangeListenerCaptor.capture())
        configChangeListener = configChangeListenerCaptor.value

        verify(statusBarStateController).addCallback(statusBarStateListenerCaptor.capture())
        statusBarStateListener = statusBarStateListenerCaptor.value

        verify(smartspaceSession).requestSmartspaceUpdate()
        clearInvocations(smartspaceSession)

        verify(fakeSmartspaceView).setPrimaryTextColor(anyInt())
        verify(fakeSmartspaceView).setDozeAmount(0.5f)
        clearInvocations(fakeSmartspaceView)

        fakeParent.addView(fakeSmartspaceView)
    }

    private fun setActiveUser(userHandle: UserHandle) {
        `when`(userTracker.userId).thenReturn(userHandle.identifier)
        `when`(userTracker.userHandle).thenReturn(userHandle)
    }

    private fun mockUserInfo(userHandle: UserHandle, isManagedProfile: Boolean): UserInfo {
        val userInfo = mock(UserInfo::class.java)
        `when`(userInfo.userHandle).thenReturn(userHandle)
        `when`(userInfo.isManagedProfile).thenReturn(isManagedProfile)
        return userInfo
    }

    fun makeTarget(
        id: Int,
        userHandle: UserHandle,
        isSensitive: Boolean = false
    ): SmartspaceTarget {
        return SmartspaceTarget.Builder(
                "target$id",
                ComponentName("testpackage", "testclass$id"),
                userHandle)
                .setSensitive(isSensitive)
                .build()
    }

    private fun setAllowPrivateNotifications(user: UserHandle, value: Boolean) {
        `when`(secureSettings.getIntForUser(
                eq(PRIVATE_LOCKSCREEN_SETTING),
                anyInt(),
                eq(user.identifier))
        ).thenReturn(if (value) 1 else 0)
    }

    private val fakeSmartspaceView = spy(object : View(context), SmartspaceView {
        override fun registerDataProvider(plugin: BcSmartspaceDataPlugin?) {
        }

        override fun setPrimaryTextColor(color: Int) {
        }

        override fun setDozeAmount(amount: Float) {
        }

        override fun setIntentStarter(intentStarter: BcSmartspaceDataPlugin.IntentStarter?) {
        }

        override fun setFalsingManager(falsingManager: FalsingManager?) {
        }

        override fun setDnd(image: Drawable?, description: String?) {
        }

        override fun setNextAlarm(image: Drawable?, description: String?) {
        }

        override fun setMediaTarget(target: SmartspaceTarget?) {
        }
    })
}

private const val PRIVATE_LOCKSCREEN_SETTING =
        Settings.Secure.LOCK_SCREEN_ALLOW_PRIVATE_NOTIFICATIONS
