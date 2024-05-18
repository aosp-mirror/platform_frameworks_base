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

package com.android.systemui.statusbar.policy

import android.content.ComponentName
import android.content.Context
import android.content.pm.ServiceInfo
import android.provider.Settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest

import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.dagger.ControlsComponent
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.controller.SeedResponse
import com.android.systemui.controls.controller.StructureInfo
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.management.ControlsListingController.ControlsListingCallback
import com.android.systemui.settings.UserContextProvider
import com.android.systemui.statusbar.policy.DeviceControlsControllerImpl.Companion.PREFS_CONTROLS_FILE
import com.android.systemui.statusbar.policy.DeviceControlsControllerImpl.Companion.PREFS_CONTROLS_SEEDING_COMPLETED
import com.android.systemui.statusbar.policy.DeviceControlsControllerImpl.Companion.QS_DEFAULT_POSITION
import com.android.systemui.statusbar.policy.DeviceControlsControllerImpl.Companion.QS_PRIORITY_POSITION
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.settings.SecureSettings

import java.util.Optional
import java.util.function.Consumer

import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyObject

@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceControlsControllerImplTest : SysuiTestCase() {

    @Mock
    private lateinit var controlsComponent: ControlsComponent
    @Mock
    private lateinit var controlsController: ControlsController
    @Mock
    private lateinit var controlsListingController: ControlsListingController
    @Mock
    private lateinit var callback: DeviceControlsController.Callback
    @Captor
    private lateinit var listingCallbackCaptor: ArgumentCaptor<ControlsListingCallback>
    @Mock
    private lateinit var structureInfo: StructureInfo
    @Mock
    private lateinit var serviceInfo: ServiceInfo
    @Mock
    private lateinit var userContextProvider: UserContextProvider
    @Mock
    private lateinit var secureSettings: SecureSettings
    @Captor
    private lateinit var seedCallback: ArgumentCaptor<Consumer<SeedResponse>>

    private lateinit var controlsServiceInfo: ControlsServiceInfo
    private lateinit var controller: DeviceControlsControllerImpl

    companion object {
        fun <T> capture(argumentCaptor: ArgumentCaptor<T>): T = argumentCaptor.capture()
        fun <T> eq(value: T): T = Mockito.eq(value) ?: value
        private val TEST_PKG = "test.pkg"
        private val TEST_COMPONENT = ComponentName(TEST_PKG, "test.class")
    }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        `when`(controlsComponent.getControlsController())
                .thenReturn(Optional.of(controlsController))
        `when`(controlsComponent.getControlsListingController())
                .thenReturn(Optional.of(controlsListingController))

        `when`(controlsComponent.isEnabled()).thenReturn(true)

        controller = DeviceControlsControllerImpl(
            mContext,
            controlsComponent,
            userContextProvider,
            secureSettings
        )

        `when`(secureSettings.getInt(Settings.Secure.CONTROLS_ENABLED, 1)).thenReturn(1)

        `when`(serviceInfo.componentName).thenReturn(TEST_COMPONENT)
        controlsServiceInfo = ControlsServiceInfo(mContext, serviceInfo)

        `when`(userContextProvider.userContext).thenReturn(mContext)
        mContext.getSharedPreferences(PREFS_CONTROLS_FILE, Context.MODE_PRIVATE).edit()
            .putStringSet(PREFS_CONTROLS_SEEDING_COMPLETED, emptySet())
            .apply()
    }

    @Test
    fun testNoCallbackWhenNoServicesAvailable() {
        `when`(controlsController.getFavorites()).thenReturn(emptyList())
        controller.setCallback(callback)

        verify(controlsListingController).addCallback(capture(listingCallbackCaptor))
        listingCallbackCaptor.value.onServicesUpdated(emptyList())
        verify(callback, never()).onControlsUpdate(anyInt())
    }

    @Test
    fun testCallbackWithNullValueWhenSettingIsDisabled() {
        `when`(secureSettings.getInt(Settings.Secure.CONTROLS_ENABLED, 1)).thenReturn(0)
        controller.setCallback(callback)

        verify(controlsListingController, never()).addCallback(anyObject())
        verify(callback).onControlsUpdate(null)
    }

    @Test
    fun testSetPriorityPositionIsSetWhenFavoritesAreAvailable() {
        `when`(controlsController.getFavorites()).thenReturn(listOf(structureInfo))
        controller.setCallback(callback)

        verify(controlsListingController).addCallback(capture(listingCallbackCaptor))
        listingCallbackCaptor.value.onServicesUpdated(listOf(controlsServiceInfo))
        verify(callback).onControlsUpdate(QS_PRIORITY_POSITION)
    }

    @Test
    fun testSetDefaultPositionIsSetWhenNoFavoritesAreAvailable() {
        `when`(controlsController.getFavorites()).thenReturn(emptyList())
        controller.setCallback(callback)

        mContext.getOrCreateTestableResources().addOverride(
                R.array.config_controlsPreferredPackages,
                arrayOf(TEST_PKG))

        verify(controlsListingController).addCallback(capture(listingCallbackCaptor))
        listingCallbackCaptor.value.onServicesUpdated(listOf(controlsServiceInfo))

        verify(controlsController).seedFavoritesForComponents(
            eq(listOf(TEST_COMPONENT)),
            capture(seedCallback)
        )
        seedCallback.value.accept(SeedResponse(TEST_PKG, true))
        verify(callback).onControlsUpdate(QS_DEFAULT_POSITION)
    }

    @Test
    fun testControlsDisabledRemoveFromAutoTracker() {
        `when`(controlsComponent.isEnabled()).thenReturn(false)
        val callback: DeviceControlsController.Callback = mock()

        controller.setCallback(callback)

        verify(callback).removeControlsAutoTracker()
        verify(callback, never()).onControlsUpdate(anyInt())
    }
}
