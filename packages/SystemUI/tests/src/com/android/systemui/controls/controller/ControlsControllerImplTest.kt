/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.controls.controller

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.UserHandle
import android.provider.Settings
import android.service.controls.Control
import android.service.controls.DeviceTypes
import android.service.controls.actions.ControlAction
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.DumpController
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.ControlStatus
import com.android.systemui.controls.ui.ControlsUiController
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.util.Optional

@SmallTest
@RunWith(AndroidTestingRunner::class)
class ControlsControllerImplTest : SysuiTestCase() {

    @Mock
    private lateinit var uiController: ControlsUiController
    @Mock
    private lateinit var bindingController: ControlsBindingController
    @Mock
    private lateinit var dumpController: DumpController
    @Mock
    private lateinit var pendingIntent: PendingIntent
    @Mock
    private lateinit var persistenceWrapper: ControlsFavoritePersistenceWrapper
    @Mock
    private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock
    private lateinit var listingController: ControlsListingController

    @Captor
    private lateinit var controlInfoListCaptor: ArgumentCaptor<List<ControlInfo>>
    @Captor
    private lateinit var controlLoadCallbackCaptor: ArgumentCaptor<(List<Control>) -> Unit>
    @Captor
    private lateinit var broadcastReceiverCaptor: ArgumentCaptor<BroadcastReceiver>

    private lateinit var delayableExecutor: FakeExecutor
    private lateinit var controller: ControlsController

    companion object {
        fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()
        fun <T> eq(value: T): T = Mockito.eq(value) ?: value
        fun <T> any(): T = Mockito.any<T>()

        private val TEST_COMPONENT = ComponentName("test.pkg", "test.class")
        private const val TEST_CONTROL_ID = "control1"
        private const val TEST_CONTROL_TITLE = "Test"
        private const val TEST_DEVICE_TYPE = DeviceTypes.TYPE_AC_HEATER
        private val TEST_CONTROL_INFO = ControlInfo(
                TEST_COMPONENT, TEST_CONTROL_ID, TEST_CONTROL_TITLE, TEST_DEVICE_TYPE)

        private val TEST_COMPONENT_2 = ComponentName("test.pkg", "test.class.2")
        private const val TEST_CONTROL_ID_2 = "control2"
        private const val TEST_CONTROL_TITLE_2 = "Test 2"
        private const val TEST_DEVICE_TYPE_2 = DeviceTypes.TYPE_CAMERA
        private val TEST_CONTROL_INFO_2 = ControlInfo(
                TEST_COMPONENT_2, TEST_CONTROL_ID_2, TEST_CONTROL_TITLE_2, TEST_DEVICE_TYPE_2)
    }

    private val user = mContext.userId
    private val otherUser = user + 1

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        Settings.Secure.putInt(mContext.contentResolver,
                ControlsControllerImpl.CONTROLS_AVAILABLE, 1)
        Settings.Secure.putIntForUser(mContext.contentResolver,
                ControlsControllerImpl.CONTROLS_AVAILABLE, 1, otherUser)

        delayableExecutor = FakeExecutor(FakeSystemClock())

        val wrapper = object : ContextWrapper(mContext) {
            override fun createContextAsUser(user: UserHandle, flags: Int): Context {
                return baseContext
            }
        }

        controller = ControlsControllerImpl(
                wrapper,
                delayableExecutor,
                uiController,
                bindingController,
                listingController,
                broadcastDispatcher,
                Optional.of(persistenceWrapper),
                dumpController
        )
        assertTrue(controller.available)
        verify(broadcastDispatcher).registerReceiver(
                capture(broadcastReceiverCaptor), any(), any(), eq(UserHandle.ALL))
    }

    private fun builderFromInfo(controlInfo: ControlInfo): Control.StatelessBuilder {
        return Control.StatelessBuilder(controlInfo.controlId, pendingIntent)
                .setDeviceType(controlInfo.deviceType).setTitle(controlInfo.controlTitle)
    }

    @Test
    fun testStartOnUser() {
        assertEquals(user, controller.currentUserId)
    }

    @Test
    fun testStartWithoutFavorites() {
        assertTrue(controller.getFavoriteControls().isEmpty())
    }

    @Test
    fun testStartWithSavedFavorites() {
        `when`(persistenceWrapper.readFavorites()).thenReturn(listOf(TEST_CONTROL_INFO))
        val controller_other = ControlsControllerImpl(
                mContext,
                delayableExecutor,
                uiController,
                bindingController,
                listingController,
                broadcastDispatcher,
                Optional.of(persistenceWrapper),
                dumpController
        )
        assertEquals(listOf(TEST_CONTROL_INFO), controller_other.getFavoriteControls())
    }

    @Test
    fun testAddFavorite() {
        controller.changeFavoriteStatus(TEST_CONTROL_INFO, true)

        val favorites = controller.getFavoriteControls()
        assertTrue(TEST_CONTROL_INFO in favorites)
        assertEquals(1, favorites.size)
    }

    @Test
    fun testAddMultipleFavorites() {
        controller.changeFavoriteStatus(TEST_CONTROL_INFO, true)
        controller.changeFavoriteStatus(TEST_CONTROL_INFO_2, true)

        val favorites = controller.getFavoriteControls()
        assertTrue(TEST_CONTROL_INFO in favorites)
        assertTrue(TEST_CONTROL_INFO_2 in favorites)
        assertEquals(2, favorites.size)
    }

    @Test
    fun testAddAndRemoveFavorite() {
        controller.changeFavoriteStatus(TEST_CONTROL_INFO, true)
        controller.changeFavoriteStatus(TEST_CONTROL_INFO_2, true)
        controller.changeFavoriteStatus(TEST_CONTROL_INFO, false)

        val favorites = controller.getFavoriteControls()
        assertTrue(TEST_CONTROL_INFO !in favorites)
        assertTrue(TEST_CONTROL_INFO_2 in favorites)
        assertEquals(1, favorites.size)
    }

    @Test
    fun testFavoritesSavedOnAdd() {
        controller.changeFavoriteStatus(TEST_CONTROL_INFO, true)

        verify(persistenceWrapper).storeFavorites(listOf(TEST_CONTROL_INFO))
    }

    @Test
    fun testFavoritesSavedOnRemove() {
        controller.changeFavoriteStatus(TEST_CONTROL_INFO, true)
        reset(persistenceWrapper)

        controller.changeFavoriteStatus(TEST_CONTROL_INFO, false)
        verify(persistenceWrapper).storeFavorites(emptyList())
    }

    @Test
    fun testFavoritesSavedOnChange() {
        controller.changeFavoriteStatus(TEST_CONTROL_INFO, true)
        val newControlInfo = TEST_CONTROL_INFO.copy(controlTitle = TEST_CONTROL_TITLE_2)
        val control = builderFromInfo(newControlInfo).build()

        controller.loadForComponent(TEST_COMPONENT) {}

        reset(persistenceWrapper)
        verify(bindingController).bindAndLoad(eq(TEST_COMPONENT),
                capture(controlLoadCallbackCaptor))

        controlLoadCallbackCaptor.value.invoke(listOf(control))

        verify(persistenceWrapper).storeFavorites(listOf(newControlInfo))
    }

    @Test
    fun testFavoritesNotSavedOnRedundantAdd() {
        controller.changeFavoriteStatus(TEST_CONTROL_INFO, true)

        reset(persistenceWrapper)

        controller.changeFavoriteStatus(TEST_CONTROL_INFO, true)
        verify(persistenceWrapper, never()).storeFavorites(ArgumentMatchers.anyList())
    }

    @Test
    fun testFavoritesNotSavedOnNotRemove() {
        controller.changeFavoriteStatus(TEST_CONTROL_INFO, false)
        verify(persistenceWrapper, never()).storeFavorites(ArgumentMatchers.anyList())
    }

    @Test
    fun testOnActionResponse() {
        controller.onActionResponse(TEST_COMPONENT, TEST_CONTROL_ID, ControlAction.RESPONSE_OK)

        verify(uiController).onActionResponse(TEST_COMPONENT, TEST_CONTROL_ID,
                ControlAction.RESPONSE_OK)
    }

    @Test
    fun testRefreshStatus() {
        val control = Control.StatefulBuilder(TEST_CONTROL_ID, pendingIntent).build()
        val list = listOf(control)
        controller.refreshStatus(TEST_COMPONENT, control)

        verify(uiController).onRefreshState(TEST_COMPONENT, list)
    }

    @Test
    fun testUnsubscribe() {
        controller.unsubscribe()
        verify(bindingController).unsubscribe()
    }

    @Test
    fun testSubscribeFavorites() {
        controller.changeFavoriteStatus(TEST_CONTROL_INFO, true)
        controller.changeFavoriteStatus(TEST_CONTROL_INFO_2, true)

        controller.subscribeToFavorites()

        verify(bindingController).subscribe(capture(controlInfoListCaptor))

        assertTrue(TEST_CONTROL_INFO in controlInfoListCaptor.value)
        assertTrue(TEST_CONTROL_INFO_2 in controlInfoListCaptor.value)
    }

    @Test
    fun testLoadForComponent_noFavorites() {
        var loaded = false
        val control = builderFromInfo(TEST_CONTROL_INFO).build()

        controller.loadForComponent(TEST_COMPONENT) {
            loaded = true
            assertEquals(1, it.size)
            val controlStatus = it[0]
            assertEquals(ControlStatus(control, false), controlStatus)
        }

        verify(bindingController).bindAndLoad(eq(TEST_COMPONENT),
                capture(controlLoadCallbackCaptor))

        controlLoadCallbackCaptor.value.invoke(listOf(control))

        assertTrue(loaded)
    }

    @Test
    fun testLoadForComponent_favorites() {
        var loaded = false
        val control = builderFromInfo(TEST_CONTROL_INFO).build()
        val control2 = builderFromInfo(TEST_CONTROL_INFO_2).build()
        controller.changeFavoriteStatus(TEST_CONTROL_INFO, true)

        controller.loadForComponent(TEST_COMPONENT) {
            loaded = true
            assertEquals(2, it.size)
            val controlStatus = it.first { it.control.controlId == TEST_CONTROL_ID }
            assertEquals(ControlStatus(control, true), controlStatus)

            val controlStatus2 = it.first { it.control.controlId == TEST_CONTROL_ID_2 }
            assertEquals(ControlStatus(control2, false), controlStatus2)
        }

        verify(bindingController).bindAndLoad(eq(TEST_COMPONENT),
                capture(controlLoadCallbackCaptor))

        controlLoadCallbackCaptor.value.invoke(listOf(control, control2))

        assertTrue(loaded)
    }

    @Test
    fun testLoadForComponent_removed() {
        var loaded = false
        controller.changeFavoriteStatus(TEST_CONTROL_INFO, true)

        controller.loadForComponent(TEST_COMPONENT) {
            loaded = true
            assertEquals(1, it.size)
            val controlStatus = it[0]
            assertEquals(TEST_CONTROL_ID, controlStatus.control.controlId)
            assertTrue(controlStatus.favorite)
            assertTrue(controlStatus.removed)
        }

        verify(bindingController).bindAndLoad(eq(TEST_COMPONENT),
                capture(controlLoadCallbackCaptor))

        controlLoadCallbackCaptor.value.invoke(emptyList())

        assertTrue(loaded)
    }

    @Test
    fun testFavoriteInformationModifiedOnLoad() {
        controller.changeFavoriteStatus(TEST_CONTROL_INFO, true)
        val newControlInfo = TEST_CONTROL_INFO.copy(controlTitle = TEST_CONTROL_TITLE_2)
        val control = builderFromInfo(newControlInfo).build()

        controller.loadForComponent(TEST_COMPONENT) {}

        verify(bindingController).bindAndLoad(eq(TEST_COMPONENT),
                capture(controlLoadCallbackCaptor))

        controlLoadCallbackCaptor.value.invoke(listOf(control))

        val favorites = controller.getFavoriteControls()
        assertEquals(1, favorites.size)
        assertEquals(newControlInfo, favorites[0])
    }

    @Test
    fun testFavoriteInformationModifiedOnRefresh() {
        controller.changeFavoriteStatus(TEST_CONTROL_INFO, true)
        val newControlInfo = TEST_CONTROL_INFO.copy(controlTitle = TEST_CONTROL_TITLE_2)
        val control = builderFromInfo(newControlInfo).build()

        controller.refreshStatus(TEST_COMPONENT, control)

        delayableExecutor.runAllReady()

        val favorites = controller.getFavoriteControls()
        assertEquals(1, favorites.size)
        assertEquals(newControlInfo, favorites[0])
    }

    @Test
    fun testClearFavorites() {
        controller.changeFavoriteStatus(TEST_CONTROL_INFO, true)
        assertEquals(1, controller.getFavoriteControls().size)

        controller.clearFavorites()
        assertTrue(controller.getFavoriteControls().isEmpty())
    }

    @Test
    fun testSwitchUsers() {
        controller.changeFavoriteStatus(TEST_CONTROL_INFO, true)

        reset(persistenceWrapper)
        val intent = Intent(Intent.ACTION_USER_SWITCHED).apply {
            putExtra(Intent.EXTRA_USER_HANDLE, otherUser)
        }
        val pendingResult = mock(BroadcastReceiver.PendingResult::class.java)
        `when`(pendingResult.sendingUserId).thenReturn(otherUser)
        broadcastReceiverCaptor.value.pendingResult = pendingResult

        broadcastReceiverCaptor.value.onReceive(mContext, intent)

        verify(persistenceWrapper).changeFile(any())
        verify(persistenceWrapper).readFavorites()
        verify(bindingController).changeUser(UserHandle.of(otherUser))
        verify(listingController).changeUser(UserHandle.of(otherUser))
        assertTrue(controller.getFavoriteControls().isEmpty())
        assertEquals(otherUser, controller.currentUserId)
    }
}