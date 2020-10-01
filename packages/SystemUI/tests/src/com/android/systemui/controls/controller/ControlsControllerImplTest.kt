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
import android.content.pm.ServiceInfo
import android.os.UserHandle
import android.provider.Settings
import android.service.controls.Control
import android.service.controls.DeviceTypes
import android.service.controls.actions.ControlAction
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.backup.BackupHelper
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.controls.ControlStatus
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.ui.ControlsUiController
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations
import java.util.Optional
import java.util.function.Consumer

@SmallTest
@RunWith(AndroidTestingRunner::class)
class ControlsControllerImplTest : SysuiTestCase() {

    @Mock
    private lateinit var uiController: ControlsUiController
    @Mock
    private lateinit var bindingController: ControlsBindingController
    @Mock
    private lateinit var pendingIntent: PendingIntent
    @Mock
    private lateinit var persistenceWrapper: ControlsFavoritePersistenceWrapper
    @Mock
    private lateinit var auxiliaryPersistenceWrapper: AuxiliaryPersistenceWrapper
    @Mock
    private lateinit var broadcastDispatcher: BroadcastDispatcher
    @Mock
    private lateinit var listingController: ControlsListingController

    @Captor
    private lateinit var structureInfoCaptor: ArgumentCaptor<StructureInfo>

    @Captor
    private lateinit var controlLoadCallbackCaptor:
            ArgumentCaptor<ControlsBindingController.LoadCallback>
    @Captor
    private lateinit var controlLoadCallbackCaptor2:
            ArgumentCaptor<ControlsBindingController.LoadCallback>

    @Captor
    private lateinit var broadcastReceiverCaptor: ArgumentCaptor<BroadcastReceiver>
    @Captor
    private lateinit var listingCallbackCaptor:
            ArgumentCaptor<ControlsListingController.ControlsListingCallback>

    private lateinit var delayableExecutor: FakeExecutor
    private lateinit var controller: ControlsControllerImpl
    private lateinit var canceller: DidRunRunnable

    companion object {
        fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()
        fun <T> eq(value: T): T = Mockito.eq(value) ?: value
        fun <T> any(): T = Mockito.any<T>()

        private val TEST_COMPONENT = ComponentName("test.pkg", "test.class")
        private const val TEST_CONTROL_ID = "control1"
        private const val TEST_CONTROL_TITLE = "Test"
        private const val TEST_CONTROL_SUBTITLE = "TestSub"
        private const val TEST_DEVICE_TYPE = DeviceTypes.TYPE_AC_HEATER
        private const val TEST_STRUCTURE = ""
        private val TEST_CONTROL_INFO = ControlInfo(TEST_CONTROL_ID,
                TEST_CONTROL_TITLE, TEST_CONTROL_SUBTITLE, TEST_DEVICE_TYPE)
        private val TEST_STRUCTURE_INFO = StructureInfo(TEST_COMPONENT,
                TEST_STRUCTURE, listOf(TEST_CONTROL_INFO))

        private val TEST_COMPONENT_2 = ComponentName("test.pkg", "test.class.2")
        private const val TEST_CONTROL_ID_2 = "control2"
        private const val TEST_CONTROL_TITLE_2 = "Test 2"
        private const val TEST_CONTROL_SUBTITLE_2 = "TestSub 2"
        private const val TEST_DEVICE_TYPE_2 = DeviceTypes.TYPE_CAMERA
        private const val TEST_STRUCTURE_2 = "My House"
        private val TEST_CONTROL_INFO_2 = ControlInfo(TEST_CONTROL_ID_2,
                TEST_CONTROL_TITLE_2, TEST_CONTROL_SUBTITLE_2, TEST_DEVICE_TYPE_2)
        private val TEST_STRUCTURE_INFO_2 = StructureInfo(TEST_COMPONENT_2,
                TEST_STRUCTURE_2, listOf(TEST_CONTROL_INFO_2))
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

        canceller = DidRunRunnable()
        `when`(bindingController.bindAndLoad(any(), any())).thenReturn(canceller)

        controller = ControlsControllerImpl(
                wrapper,
                delayableExecutor,
                uiController,
                bindingController,
                listingController,
                broadcastDispatcher,
                Optional.of(persistenceWrapper),
                mock(DumpManager::class.java)
        )
        controller.auxiliaryPersistenceWrapper = auxiliaryPersistenceWrapper

        assertTrue(controller.available)
        verify(broadcastDispatcher).registerReceiver(
                capture(broadcastReceiverCaptor), any(), any(), eq(UserHandle.ALL))

        verify(listingController).addCallback(capture(listingCallbackCaptor))
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    private fun statelessBuilderFromInfo(
        controlInfo: ControlInfo,
        structure: CharSequence = ""
    ): Control.StatelessBuilder {
        return Control.StatelessBuilder(controlInfo.controlId, pendingIntent)
                .setDeviceType(controlInfo.deviceType).setTitle(controlInfo.controlTitle)
                .setSubtitle(controlInfo.controlSubtitle).setStructure(structure)
    }

    private fun statefulBuilderFromInfo(
        controlInfo: ControlInfo,
        structure: CharSequence = ""
    ): Control.StatefulBuilder {
        return Control.StatefulBuilder(controlInfo.controlId, pendingIntent)
                .setDeviceType(controlInfo.deviceType).setTitle(controlInfo.controlTitle)
                .setSubtitle(controlInfo.controlSubtitle).setStructure(structure)
    }

    @Test
    fun testStartOnUser() {
        assertEquals(user, controller.currentUserId)
    }

    @Test
    fun testStartWithoutFavorites() {
        assertTrue(controller.getFavorites().isEmpty())
    }

    @Test
    fun testStartWithSavedFavorites() {
        `when`(persistenceWrapper.readFavorites()).thenReturn(listOf(TEST_STRUCTURE_INFO))
        val controller_other = ControlsControllerImpl(
                mContext,
                delayableExecutor,
                uiController,
                bindingController,
                listingController,
                broadcastDispatcher,
                Optional.of(persistenceWrapper),
                mock(DumpManager::class.java)
        )
        assertEquals(listOf(TEST_STRUCTURE_INFO), controller_other.getFavorites())
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
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO_2)
        delayableExecutor.runAllReady()

        controller.subscribeToFavorites(TEST_STRUCTURE_INFO)

        verify(bindingController).subscribe(capture(structureInfoCaptor))

        assertTrue(TEST_CONTROL_INFO in structureInfoCaptor.value.controls)
        assertFalse(TEST_CONTROL_INFO_2 in structureInfoCaptor.value.controls)
    }

    @Test
    fun testLoadForComponent_noFavorites() {
        var loaded = false
        val control = statelessBuilderFromInfo(TEST_CONTROL_INFO).build()

        controller.loadForComponent(TEST_COMPONENT, Consumer { data ->
            val controls = data.allControls
            val favorites = data.favoritesIds
            loaded = true
            assertEquals(1, controls.size)
            val controlStatus = controls[0]
            assertEquals(ControlStatus(control, TEST_COMPONENT, false), controlStatus)

            assertTrue(favorites.isEmpty())
            assertFalse(data.errorOnLoad)
        }, Consumer {})

        verify(bindingController).bindAndLoad(eq(TEST_COMPONENT),
                capture(controlLoadCallbackCaptor))

        controlLoadCallbackCaptor.value.accept(listOf(control))

        delayableExecutor.runAllReady()

        assertTrue(loaded)
    }

    @Test
    fun testLoadForComponent_favorites() {
        var loaded = false
        val control = statelessBuilderFromInfo(TEST_CONTROL_INFO).build()
        val control2 = statelessBuilderFromInfo(TEST_CONTROL_INFO_2).build()
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO_2)
        delayableExecutor.runAllReady()

        controller.loadForComponent(TEST_COMPONENT, Consumer { data ->
            val controls = data.allControls
            val favorites = data.favoritesIds
            loaded = true
            assertEquals(2, controls.size)
            val controlStatus = controls.first { it.control.controlId == TEST_CONTROL_ID }
            assertEquals(ControlStatus(control, TEST_COMPONENT, true), controlStatus)

            val controlStatus2 = controls.first { it.control.controlId == TEST_CONTROL_ID_2 }
            assertEquals(ControlStatus(control2, TEST_COMPONENT, false), controlStatus2)

            assertEquals(1, favorites.size)
            assertEquals(TEST_CONTROL_ID, favorites[0])
            assertFalse(data.errorOnLoad)
        }, Consumer {})

        verify(bindingController).bindAndLoad(eq(TEST_COMPONENT),
                capture(controlLoadCallbackCaptor))

        controlLoadCallbackCaptor.value.accept(listOf(control, control2))
        delayableExecutor.runAllReady()

        assertTrue(loaded)
    }

    @Test
    fun testLoadForComponent_removed() {
        var loaded = false
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)
        delayableExecutor.runAllReady()

        controller.loadForComponent(TEST_COMPONENT, Consumer { data ->
            val controls = data.allControls
            val favorites = data.favoritesIds
            loaded = true
            assertEquals(1, controls.size)
            val controlStatus = controls[0]
            assertEquals(TEST_CONTROL_ID, controlStatus.control.controlId)
            assertEquals(TEST_STRUCTURE_INFO.structure, controlStatus.control.structure)
            assertTrue(controlStatus.favorite)
            assertTrue(controlStatus.removed)

            assertEquals(1, favorites.size)
            assertEquals(TEST_CONTROL_ID, favorites[0])
            assertFalse(data.errorOnLoad)
        }, Consumer {})

        verify(bindingController).bindAndLoad(eq(TEST_COMPONENT),
                capture(controlLoadCallbackCaptor))

        controlLoadCallbackCaptor.value.accept(emptyList())
        delayableExecutor.runAllReady()

        assertTrue(loaded)
    }

    @Test
    fun testErrorOnLoad_notRemoved() {
        var loaded = false
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)
        delayableExecutor.runAllReady()

        controller.loadForComponent(TEST_COMPONENT, Consumer { data ->
            val controls = data.allControls
            val favorites = data.favoritesIds
            loaded = true
            assertEquals(1, controls.size)
            val controlStatus = controls[0]
            assertEquals(TEST_CONTROL_ID, controlStatus.control.controlId)
            assertEquals(TEST_STRUCTURE_INFO.structure, controlStatus.control.structure)
            assertTrue(controlStatus.favorite)
            assertFalse(controlStatus.removed)

            assertEquals(1, favorites.size)
            assertEquals(TEST_CONTROL_ID, favorites[0])
            assertTrue(data.errorOnLoad)
        }, Consumer {})

        verify(bindingController).bindAndLoad(eq(TEST_COMPONENT),
                capture(controlLoadCallbackCaptor))

        controlLoadCallbackCaptor.value.error("")

        delayableExecutor.runAllReady()

        assertTrue(loaded)
    }

    @Test
    fun testCancelLoad() {
        var loaded = false
        var cancelRunnable: Runnable? = null
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)
        delayableExecutor.runAllReady()
        controller.loadForComponent(TEST_COMPONENT, Consumer {
            loaded = true
        }, Consumer { runnable -> cancelRunnable = runnable })

        cancelRunnable?.run()
        delayableExecutor.runAllReady()

        assertFalse(loaded)
        assertTrue(canceller.ran)
    }

    @Test
    fun testCancelLoad_afterSuccessfulLoad() {
        var loaded = false
        var cancelRunnable: Runnable? = null
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)
        delayableExecutor.runAllReady()
        controller.loadForComponent(TEST_COMPONENT, Consumer {
            loaded = true
        }, Consumer { runnable -> cancelRunnable = runnable })

        verify(bindingController).bindAndLoad(eq(TEST_COMPONENT),
            capture(controlLoadCallbackCaptor))

        controlLoadCallbackCaptor.value.accept(emptyList())

        cancelRunnable?.run()
        delayableExecutor.runAllReady()

        assertTrue(loaded)
        assertTrue(canceller.ran)
    }

    @Test
    fun testCancelLoad_afterErrorLoad() {
        var loaded = false
        var cancelRunnable: Runnable? = null
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)
        delayableExecutor.runAllReady()
        controller.loadForComponent(TEST_COMPONENT, Consumer {
            loaded = true
        }, Consumer { runnable -> cancelRunnable = runnable })

        verify(bindingController).bindAndLoad(eq(TEST_COMPONENT),
            capture(controlLoadCallbackCaptor))

        controlLoadCallbackCaptor.value.error("")

        cancelRunnable?.run()
        delayableExecutor.runAllReady()

        assertTrue(loaded)
        assertTrue(canceller.ran)
    }

    @Test
    fun testFavoriteInformationModifiedOnLoad() {
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)
        delayableExecutor.runAllReady()

        val newControlInfo = TEST_CONTROL_INFO.copy(controlTitle = TEST_CONTROL_TITLE_2)
        val control = statelessBuilderFromInfo(newControlInfo).build()

        controller.loadForComponent(TEST_COMPONENT, Consumer {}, Consumer {})

        verify(bindingController).bindAndLoad(eq(TEST_COMPONENT),
                capture(controlLoadCallbackCaptor))

        controlLoadCallbackCaptor.value.accept(listOf(control))
        delayableExecutor.runAllReady()

        val favorites = controller.getFavorites().flatMap { it.controls }
        assertEquals(1, favorites.size)
        assertEquals(newControlInfo, favorites[0])
    }

    @Test
    fun testFavoriteInformationModifiedOnRefreshWithOkStatus() {
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)

        val newControlInfo = TEST_CONTROL_INFO.copy(controlTitle = TEST_CONTROL_TITLE_2)
        val control = statefulBuilderFromInfo(newControlInfo).setStatus(Control.STATUS_OK).build()

        controller.refreshStatus(TEST_COMPONENT, control)

        delayableExecutor.runAllReady()

        val favorites = controller.getFavorites().flatMap { it.controls }
        assertEquals(1, favorites.size)
        assertEquals(newControlInfo, favorites[0])
    }

    @Test
    fun testFavoriteInformationNotModifiedOnRefreshWithNonOkStatus() {
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)

        val newControlInfo = TEST_CONTROL_INFO.copy(controlTitle = TEST_CONTROL_TITLE_2)
        val control = statefulBuilderFromInfo(newControlInfo).setStatus(Control.STATUS_ERROR)
            .build()

        controller.refreshStatus(TEST_COMPONENT, control)

        delayableExecutor.runAllReady()

        val favorites = controller.getFavorites().flatMap { it.controls }
        assertEquals(1, favorites.size)
        assertEquals(TEST_CONTROL_INFO, favorites[0])
    }

    @Test
    fun testSwitchUsers() {
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)
        delayableExecutor.runAllReady()

        reset(persistenceWrapper)
        val intent = Intent(Intent.ACTION_USER_SWITCHED).apply {
            putExtra(Intent.EXTRA_USER_HANDLE, otherUser)
        }
        val pendingResult = mock(BroadcastReceiver.PendingResult::class.java)
        `when`(pendingResult.sendingUserId).thenReturn(otherUser)
        broadcastReceiverCaptor.value.pendingResult = pendingResult

        broadcastReceiverCaptor.value.onReceive(mContext, intent)

        verify(persistenceWrapper).changeFileAndBackupManager(any(), any())
        verify(persistenceWrapper).readFavorites()
        verify(auxiliaryPersistenceWrapper).changeFile(any())
        verify(bindingController).changeUser(UserHandle.of(otherUser))
        verify(listingController).changeUser(UserHandle.of(otherUser))
        assertTrue(controller.getFavorites().isEmpty())
        assertEquals(otherUser, controller.currentUserId)
        assertTrue(controller.available)
    }

    @Test
    fun testDisableFeature_notAvailable() {
        Settings.Secure.putIntForUser(mContext.contentResolver,
                ControlsControllerImpl.CONTROLS_AVAILABLE, 0, user)
        controller.settingObserver.onChange(false, listOf(ControlsControllerImpl.URI), 0, 0)
        assertFalse(controller.available)
    }

    @Test
    fun testDisableFeature_clearFavorites() {
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)
        delayableExecutor.runAllReady()

        assertFalse(controller.getFavorites().isEmpty())

        Settings.Secure.putIntForUser(mContext.contentResolver,
                ControlsControllerImpl.CONTROLS_AVAILABLE, 0, user)
        controller.settingObserver.onChange(false, listOf(ControlsControllerImpl.URI), 0, user)
        assertTrue(controller.getFavorites().isEmpty())
    }

    @Test
    fun testDisableFeature_noChangeForNotCurrentUser() {
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)
        delayableExecutor.runAllReady()

        Settings.Secure.putIntForUser(mContext.contentResolver,
                ControlsControllerImpl.CONTROLS_AVAILABLE, 0, otherUser)
        controller.settingObserver.onChange(false, listOf(ControlsControllerImpl.URI), 0, otherUser)

        assertTrue(controller.available)
        assertFalse(controller.getFavorites().isEmpty())
    }

    @Test
    fun testCorrectUserSettingOnUserChange() {
        Settings.Secure.putIntForUser(mContext.contentResolver,
                ControlsControllerImpl.CONTROLS_AVAILABLE, 0, otherUser)

        val intent = Intent(Intent.ACTION_USER_SWITCHED).apply {
            putExtra(Intent.EXTRA_USER_HANDLE, otherUser)
        }
        val pendingResult = mock(BroadcastReceiver.PendingResult::class.java)
        `when`(pendingResult.sendingUserId).thenReturn(otherUser)
        broadcastReceiverCaptor.value.pendingResult = pendingResult

        broadcastReceiverCaptor.value.onReceive(mContext, intent)

        assertFalse(controller.available)
    }

    @Test
    fun testCountFavoritesForComponent_singleComponent() {
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)
        delayableExecutor.runAllReady()

        assertEquals(1, controller.countFavoritesForComponent(TEST_COMPONENT))
        assertEquals(0, controller.countFavoritesForComponent(TEST_COMPONENT_2))
    }

    @Test
    fun testCountFavoritesForComponent_multipleComponents() {
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO_2)
        delayableExecutor.runAllReady()

        assertEquals(1, controller.countFavoritesForComponent(TEST_COMPONENT))
        assertEquals(1, controller.countFavoritesForComponent(TEST_COMPONENT_2))
    }

    @Test
    fun testGetFavoritesForComponent() {
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)
        delayableExecutor.runAllReady()

        assertEquals(listOf(TEST_STRUCTURE_INFO),
            controller.getFavoritesForComponent(TEST_COMPONENT))
    }

    @Test
    fun testGetFavoritesForComponent_otherComponent() {
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO_2)
        delayableExecutor.runAllReady()

        assertTrue(controller.getFavoritesForComponent(TEST_COMPONENT).isEmpty())
    }

    @Test
    fun testGetFavoritesForComponent_multipleInOrder() {
        val controlInfo = ControlInfo("id", "title", "subtitle", 0)

        controller.replaceFavoritesForStructure(
            StructureInfo(
                TEST_COMPONENT,
                "Home",
                listOf(TEST_CONTROL_INFO, controlInfo)
        ))
        delayableExecutor.runAllReady()

        assertEquals(listOf(TEST_CONTROL_INFO, controlInfo),
            controller.getFavoritesForComponent(TEST_COMPONENT).flatMap { it.controls })

        controller.replaceFavoritesForStructure(
            StructureInfo(
                TEST_COMPONENT,
                "Home",
                listOf(controlInfo, TEST_CONTROL_INFO)
        ))
        delayableExecutor.runAllReady()

        assertEquals(listOf(controlInfo, TEST_CONTROL_INFO),
            controller.getFavoritesForComponent(TEST_COMPONENT).flatMap { it.controls })
    }

    @Test
    fun testReplaceFavoritesForStructure_noExistingFavorites() {
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)
        delayableExecutor.runAllReady()

        assertEquals(1, controller.countFavoritesForComponent(TEST_COMPONENT))
        assertEquals(listOf(TEST_STRUCTURE_INFO),
            controller.getFavoritesForComponent(TEST_COMPONENT))
    }

    @Test
    fun testReplaceFavoritesForStructure_doNotStoreEmptyStructure() {
        controller.replaceFavoritesForStructure(
            StructureInfo(TEST_COMPONENT, "Home", emptyList<ControlInfo>()))
        delayableExecutor.runAllReady()

        assertEquals(0, controller.countFavoritesForComponent(TEST_COMPONENT))
        assertEquals(emptyList<ControlInfo>(), controller.getFavoritesForComponent(TEST_COMPONENT))
    }

    @Test
    fun testReplaceFavoritesForStructure_differentComponentsAreFilteredOut() {
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO_2)
        delayableExecutor.runAllReady()

        assertEquals(1, controller.countFavoritesForComponent(TEST_COMPONENT))
        assertEquals(listOf(TEST_CONTROL_INFO),
            controller.getFavoritesForComponent(TEST_COMPONENT).flatMap { it.controls })
    }

    @Test
    fun testReplaceFavoritesForStructure_oldFavoritesRemoved() {
        val controlInfo = ControlInfo("id", "title", "subtitle", 0)
        assertNotEquals(TEST_CONTROL_INFO, controlInfo)

        val newComponent = ComponentName("test.pkg", "test.class.3")

        controller.replaceFavoritesForStructure(
            StructureInfo(
                newComponent,
                "Home",
                listOf(controlInfo)
        ))
        controller.replaceFavoritesForStructure(
            StructureInfo(
                newComponent,
                "Home",
                listOf(TEST_CONTROL_INFO)
        ))
        delayableExecutor.runAllReady()

        assertEquals(1, controller.countFavoritesForComponent(newComponent))
        assertEquals(listOf(TEST_CONTROL_INFO), controller
            .getFavoritesForComponent(newComponent).flatMap { it.controls })
    }

    @Test
    fun testReplaceFavoritesForStructure_favoritesInOrder() {
        val controlInfo = ControlInfo("id", "title", "subtitle", 0)

        val listOrder1 = listOf(TEST_CONTROL_INFO, controlInfo)
        val structure1 = StructureInfo(TEST_COMPONENT, "Home", listOrder1)
        controller.replaceFavoritesForStructure(structure1)
        delayableExecutor.runAllReady()

        assertEquals(2, controller.countFavoritesForComponent(TEST_COMPONENT))
        assertEquals(listOrder1, controller.getFavoritesForComponent(TEST_COMPONENT)
            .flatMap { it.controls })

        val listOrder2 = listOf(controlInfo, TEST_CONTROL_INFO)
        val structure2 = StructureInfo(TEST_COMPONENT, "Home", listOrder2)

        controller.replaceFavoritesForStructure(structure2)
        delayableExecutor.runAllReady()

        assertEquals(2, controller.countFavoritesForComponent(TEST_COMPONENT))
        assertEquals(listOrder2, controller.getFavoritesForComponent(TEST_COMPONENT)
            .flatMap { it.controls })
    }

    @Test
    fun testPackageRemoved_noFavorites_noRemovals() {
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)
        delayableExecutor.runAllReady()

        val serviceInfo = mock(ServiceInfo::class.java)
        `when`(serviceInfo.componentName).thenReturn(TEST_COMPONENT)
        val info = ControlsServiceInfo(mContext, serviceInfo)

        // Don't want to check what happens before this call
        reset(persistenceWrapper)
        listingCallbackCaptor.value.onServicesUpdated(listOf(info))
        delayableExecutor.runAllReady()

        verify(bindingController, never()).onComponentRemoved(any())

        assertEquals(1, controller.getFavorites().size)
        assertEquals(TEST_STRUCTURE_INFO, controller.getFavorites()[0])

        verify(persistenceWrapper, never()).storeFavorites(ArgumentMatchers.anyList())
    }

    @Test
    fun testPackageRemoved_hasFavorites() {
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO_2)
        delayableExecutor.runAllReady()

        val serviceInfo = mock(ServiceInfo::class.java)
        `when`(serviceInfo.componentName).thenReturn(TEST_COMPONENT)
        val info = ControlsServiceInfo(mContext, serviceInfo)

        // Don't want to check what happens before this call
        reset(persistenceWrapper)

        listingCallbackCaptor.value.onServicesUpdated(listOf(info))
        delayableExecutor.runAllReady()

        verify(bindingController).onComponentRemoved(TEST_COMPONENT_2)

        assertEquals(1, controller.getFavorites().size)
        assertEquals(TEST_STRUCTURE_INFO, controller.getFavorites()[0])

        verify(persistenceWrapper).storeFavorites(ArgumentMatchers.anyList())
    }

    @Test
    fun testExistingPackage_removedFromCache() {
        `when`(auxiliaryPersistenceWrapper.favorites).thenReturn(
            listOf(TEST_STRUCTURE_INFO, TEST_STRUCTURE_INFO_2))

        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)
        delayableExecutor.runAllReady()

        val serviceInfo = mock(ServiceInfo::class.java)
        `when`(serviceInfo.componentName).thenReturn(TEST_COMPONENT)
        val info = ControlsServiceInfo(mContext, serviceInfo)

        listingCallbackCaptor.value.onServicesUpdated(listOf(info))
        delayableExecutor.runAllReady()

        verify(auxiliaryPersistenceWrapper).getCachedFavoritesAndRemoveFor(TEST_COMPONENT)
    }

    @Test
    fun testAddedPackage_requestedFromCache() {
        `when`(auxiliaryPersistenceWrapper.favorites).thenReturn(
            listOf(TEST_STRUCTURE_INFO, TEST_STRUCTURE_INFO_2))

        val serviceInfo = mock(ServiceInfo::class.java)
        `when`(serviceInfo.componentName).thenReturn(TEST_COMPONENT)
        val info = ControlsServiceInfo(mContext, serviceInfo)

        listingCallbackCaptor.value.onServicesUpdated(listOf(info))
        delayableExecutor.runAllReady()

        verify(auxiliaryPersistenceWrapper).getCachedFavoritesAndRemoveFor(TEST_COMPONENT)
        verify(auxiliaryPersistenceWrapper, never())
                .getCachedFavoritesAndRemoveFor(TEST_COMPONENT_2)
    }

    @Test
    fun testSeedFavoritesForComponentsWithLimit() {
        var responses = mutableListOf<SeedResponse>()

        val controls1 = mutableListOf<Control>()
        for (i in 1..10) {
            controls1.add(statelessBuilderFromInfo(ControlInfo("id1:$i", TEST_CONTROL_TITLE,
                TEST_CONTROL_SUBTITLE, TEST_DEVICE_TYPE), "testStructure").build())
        }
        val controls2 = mutableListOf<Control>()
        for (i in 1..3) {
            controls2.add(statelessBuilderFromInfo(ControlInfo("id2:$i", TEST_CONTROL_TITLE,
                TEST_CONTROL_SUBTITLE, TEST_DEVICE_TYPE), "testStructure2").build())
        }
        controller.seedFavoritesForComponents(listOf(TEST_COMPONENT, TEST_COMPONENT_2), Consumer {
            resp -> responses.add(resp)
        })

        verify(bindingController).bindAndLoadSuggested(eq(TEST_COMPONENT),
                capture(controlLoadCallbackCaptor))
        controlLoadCallbackCaptor.value.accept(controls1)
        delayableExecutor.runAllReady()

        verify(bindingController).bindAndLoadSuggested(eq(TEST_COMPONENT_2),
                capture(controlLoadCallbackCaptor2))
        controlLoadCallbackCaptor2.value.accept(controls2)
        delayableExecutor.runAllReady()

        // COMPONENT 1
        val structureInfo = controller.getFavoritesForComponent(TEST_COMPONENT)[0]
        assertEquals(structureInfo.controls.size,
            ControlsControllerImpl.SUGGESTED_CONTROLS_PER_STRUCTURE)

        var i = 1
        structureInfo.controls.forEach {
            assertEquals(it.controlId, "id1:$i")
            i++
        }
        assertEquals(SeedResponse(TEST_COMPONENT.packageName, true), responses[0])

        // COMPONENT 2
        val structureInfo2 = controller.getFavoritesForComponent(TEST_COMPONENT_2)[0]
        assertEquals(structureInfo2.controls.size, 3)

        i = 1
        structureInfo2.controls.forEach {
            assertEquals(it.controlId, "id2:$i")
            i++
        }
        assertEquals(SeedResponse(TEST_COMPONENT.packageName, true), responses[1])
    }

    @Test
    fun testSeedFavoritesForComponents_error() {
        var response: SeedResponse? = null

        controller.seedFavoritesForComponents(listOf(TEST_COMPONENT), Consumer { resp ->
            response = resp
        })

        verify(bindingController).bindAndLoadSuggested(eq(TEST_COMPONENT),
                capture(controlLoadCallbackCaptor))

        controlLoadCallbackCaptor.value.error("Error loading")

        delayableExecutor.runAllReady()

        assertEquals(listOf<StructureInfo>(), controller.getFavoritesForComponent(TEST_COMPONENT))
        assertEquals(SeedResponse(TEST_COMPONENT.packageName, false), response)
    }

    @Test
    fun testSeedFavoritesForComponents_inProgressCallback() {
        var response: SeedResponse? = null
        var seeded = false
        val control = statelessBuilderFromInfo(TEST_CONTROL_INFO, TEST_STRUCTURE_INFO.structure)
            .build()

        controller.seedFavoritesForComponents(listOf(TEST_COMPONENT), Consumer { resp ->
            response = resp
        })

        verify(bindingController).bindAndLoadSuggested(eq(TEST_COMPONENT),
                capture(controlLoadCallbackCaptor))

        controller.addSeedingFavoritesCallback(Consumer { accepted ->
            seeded = accepted
        })
        controlLoadCallbackCaptor.value.accept(listOf(control))

        delayableExecutor.runAllReady()

        assertEquals(listOf(TEST_STRUCTURE_INFO),
            controller.getFavoritesForComponent(TEST_COMPONENT))
        assertEquals(SeedResponse(TEST_COMPONENT.packageName, true), response)
        assertTrue(seeded)
    }

    @Test
    fun testRestoreReceiver_loadsAuxiliaryData() {
        val receiver = controller.restoreFinishedReceiver

        val structure1 = mock(StructureInfo::class.java)
        val structure2 = mock(StructureInfo::class.java)
        val listOfStructureInfo = listOf(structure1, structure2)
        `when`(auxiliaryPersistenceWrapper.favorites).thenReturn(listOfStructureInfo)

        val intent = Intent(BackupHelper.ACTION_RESTORE_FINISHED)
        intent.putExtra(Intent.EXTRA_USER_ID, context.userId)
        receiver.onReceive(context, intent)
        delayableExecutor.runAllReady()

        val inOrder = inOrder(auxiliaryPersistenceWrapper, persistenceWrapper)
        inOrder.verify(auxiliaryPersistenceWrapper).initialize()
        inOrder.verify(auxiliaryPersistenceWrapper).favorites
        inOrder.verify(persistenceWrapper).storeFavorites(listOfStructureInfo)
        inOrder.verify(persistenceWrapper).readFavorites()
    }

    @Test
    fun testRestoreReceiver_noActionOnWrongUser() {
        val receiver = controller.restoreFinishedReceiver

        reset(persistenceWrapper)
        reset(auxiliaryPersistenceWrapper)
        val intent = Intent(BackupHelper.ACTION_RESTORE_FINISHED)
        intent.putExtra(Intent.EXTRA_USER_ID, context.userId + 1)
        receiver.onReceive(context, intent)
        delayableExecutor.runAllReady()

        verifyNoMoreInteractions(persistenceWrapper)
        verifyNoMoreInteractions(auxiliaryPersistenceWrapper)
    }

    @Test
    fun testGetFavoritesForStructure() {
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)
        controller.replaceFavoritesForStructure(
                TEST_STRUCTURE_INFO_2.copy(componentName = TEST_COMPONENT))
        delayableExecutor.runAllReady()

        assertEquals(TEST_STRUCTURE_INFO.controls,
                controller.getFavoritesForStructure(TEST_COMPONENT, TEST_STRUCTURE))
        assertEquals(TEST_STRUCTURE_INFO_2.controls,
                controller.getFavoritesForStructure(TEST_COMPONENT, TEST_STRUCTURE_2))
    }

    @Test
    fun testGetFavoritesForStructure_wrongStructure() {
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)
        delayableExecutor.runAllReady()

        assertTrue(controller.getFavoritesForStructure(TEST_COMPONENT, TEST_STRUCTURE_2).isEmpty())
    }

    @Test
    fun testGetFavoritesForStructure_wrongComponent() {
        controller.replaceFavoritesForStructure(TEST_STRUCTURE_INFO)
        delayableExecutor.runAllReady()

        assertTrue(controller.getFavoritesForStructure(TEST_COMPONENT_2, TEST_STRUCTURE).isEmpty())
    }
}

private class DidRunRunnable() : Runnable {
    var ran = false
    override fun run() {
        ran = true
    }
}
