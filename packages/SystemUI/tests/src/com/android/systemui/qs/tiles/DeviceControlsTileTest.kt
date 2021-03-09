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

package com.android.systemui.qs.tiles

import android.os.Handler
import android.provider.Settings
import android.service.quicksettings.Tile
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.lifecycle.LifecycleOwner
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.UiEventLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.dagger.ControlsComponent
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.ui.ControlsDialog
import com.android.systemui.controls.ui.ControlsUiController
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.statusbar.FeatureFlags
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.settings.FakeSettings
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SecureSettings
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.util.Optional

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class DeviceControlsTileTest : SysuiTestCase() {

    @Mock
    private lateinit var qsHost: QSHost
    @Mock
    private lateinit var metricsLogger: MetricsLogger
    @Mock
    private lateinit var statusBarStateController: StatusBarStateController
    @Mock
    private lateinit var activityStarter: ActivityStarter
    @Mock
    private lateinit var qsLogger: QSLogger
    @Mock
    private lateinit var controlsComponent: ControlsComponent
    @Mock
    private lateinit var controlsUiController: ControlsUiController
    @Mock
    private lateinit var controlsListingController: ControlsListingController
    @Mock
    private lateinit var controlsController: ControlsController
    @Mock
    private lateinit var featureFlags: FeatureFlags
    @Mock
    private lateinit var controlsDialog: ControlsDialog
    private lateinit var globalSettings: GlobalSettings
    @Mock
    private lateinit var serviceInfo: ControlsServiceInfo
    @Mock
    private lateinit var uiEventLogger: UiEventLogger
    @Captor
    private lateinit var listingCallbackCaptor:
            ArgumentCaptor<ControlsListingController.ControlsListingCallback>

    private lateinit var testableLooper: TestableLooper
    private lateinit var tile: DeviceControlsTile

    private lateinit var secureSettings: SecureSettings
    private var featureEnabled = true

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        secureSettings = FakeSettings()

        `when`(qsHost.context).thenReturn(mContext)
        `when`(qsHost.uiEventLogger).thenReturn(uiEventLogger)
        `when`(controlsController.available).thenReturn(true)
        `when`(controlsComponent.isEnabled()).thenReturn(true)
        secureSettings.putInt(Settings.Secure.POWER_MENU_LOCKED_SHOW_CONTENT, 1)

        setupControlsComponent()

        globalSettings = FakeSettings()

        globalSettings.putInt(DeviceControlsTile.SETTINGS_FLAG, 1)
        `when`(featureFlags.isKeyguardLayoutEnabled).thenReturn(true)

        tile = createTile()
    }

    private fun setupControlsComponent() {
        `when`(controlsComponent.getControlsController()).thenAnswer {
            if (featureEnabled) {
                Optional.of(controlsController)
            } else {
                Optional.empty()
            }
        }

        `when`(controlsComponent.getControlsListingController()).thenAnswer {
            if (featureEnabled) {
                Optional.of(controlsListingController)
            } else {
                Optional.empty()
            }
        }

        `when`(controlsComponent.getControlsUiController()).thenAnswer {
            if (featureEnabled) {
                Optional.of(controlsUiController)
            } else {
                Optional.empty()
            }
        }
    }

    @Test
    fun testAvailable() {
        assertThat(tile.isAvailable).isTrue()
    }

    @Test
    fun testNotAvailableFeature() {
        `when`(featureFlags.isKeyguardLayoutEnabled).thenReturn(false)

        assertThat(tile.isAvailable).isFalse()
    }

    @Test
    fun testNotAvailableControls() {
        featureEnabled = false
        tile = createTile()

        assertThat(tile.isAvailable).isFalse()
    }

    @Test
    fun testAvailableControlsSettingOff() {
        `when`(controlsController.available).thenReturn(false)

        tile = createTile()
        assertThat(tile.isAvailable).isTrue()
    }

    @Test
    fun testNotAvailableControlsLockscreenFlag() {
        globalSettings.putInt(DeviceControlsTile.SETTINGS_FLAG, 0)
        tile = createTile()

        assertThat(tile.isAvailable).isFalse()
    }

    @Test
    fun testObservingCallback() {
        verify(controlsListingController).observe(
                any(LifecycleOwner::class.java),
                any(ControlsListingController.ControlsListingCallback::class.java)
        )
    }

    @Test
    fun testLongClickIntent() {
        assertThat(tile.longClickIntent.action).isEqualTo(Settings.ACTION_DEVICE_CONTROLS_SETTINGS)
    }

    @Test
    fun testUnavailableByDefault() {
        assertThat(tile.state.state).isEqualTo(Tile.STATE_UNAVAILABLE)
    }

    @Test
    fun testStateUnavailableIfNoListings() {
        verify(controlsListingController).observe(
                any(LifecycleOwner::class.java),
                capture(listingCallbackCaptor)
        )

        listingCallbackCaptor.value.onServicesUpdated(emptyList())
        testableLooper.processAllMessages()

        assertThat(tile.state.state).isEqualTo(Tile.STATE_UNAVAILABLE)
    }

    @Test
    fun testStateUnavailableIfNotEnabled() {
        verify(controlsListingController).observe(
            any(LifecycleOwner::class.java),
            capture(listingCallbackCaptor)
        )
        `when`(controlsComponent.isEnabled()).thenReturn(false)

        listingCallbackCaptor.value.onServicesUpdated(listOf(serviceInfo))
        testableLooper.processAllMessages()

        assertThat(tile.state.state).isEqualTo(Tile.STATE_UNAVAILABLE)
    }

    @Test
    fun testStateAvailableIfListings() {
        verify(controlsListingController).observe(
                any(LifecycleOwner::class.java),
                capture(listingCallbackCaptor)
        )
        `when`(controlsComponent.getVisibility()).thenReturn(ControlsComponent.Visibility.AVAILABLE)

        listingCallbackCaptor.value.onServicesUpdated(listOf(serviceInfo))
        testableLooper.processAllMessages()

        assertThat(tile.state.state).isEqualTo(Tile.STATE_ACTIVE)
    }

    @Test
    fun testStateInactiveIfLocked() {
        verify(controlsListingController).observe(
            any(LifecycleOwner::class.java),
            capture(listingCallbackCaptor)
        )
        `when`(controlsComponent.getVisibility())
            .thenReturn(ControlsComponent.Visibility.AVAILABLE_AFTER_UNLOCK)

        listingCallbackCaptor.value.onServicesUpdated(listOf(serviceInfo))
        testableLooper.processAllMessages()

        assertThat(tile.state.state).isEqualTo(Tile.STATE_INACTIVE)
    }

    @Test
    fun testMoveBetweenStates() {
        verify(controlsListingController).observe(
                any(LifecycleOwner::class.java),
                capture(listingCallbackCaptor)
        )

        listingCallbackCaptor.value.onServicesUpdated(listOf(serviceInfo))
        testableLooper.processAllMessages()

        listingCallbackCaptor.value.onServicesUpdated(emptyList())
        testableLooper.processAllMessages()

        assertThat(tile.state.state).isEqualTo(Tile.STATE_UNAVAILABLE)
    }

    @Test
    fun testNoDialogWhenUnavailable() {
        tile.click()
        testableLooper.processAllMessages()

        verify(controlsDialog, never()).show(any(ControlsUiController::class.java))
    }

    @Test
    fun testDialogShowWhenAvailable() {
        verify(controlsListingController).observe(
                any(LifecycleOwner::class.java),
                capture(listingCallbackCaptor)
        )
        `when`(controlsComponent.getVisibility()).thenReturn(ControlsComponent.Visibility.AVAILABLE)

        listingCallbackCaptor.value.onServicesUpdated(listOf(serviceInfo))
        testableLooper.processAllMessages()

        tile.click()
        testableLooper.processAllMessages()

        verify(controlsDialog).show(controlsUiController)
    }

    @Test
    fun testNoDialogWhenInactive() {
        verify(controlsListingController).observe(
            any(LifecycleOwner::class.java),
            capture(listingCallbackCaptor)
        )
        `when`(controlsComponent.getVisibility())
            .thenReturn(ControlsComponent.Visibility.AVAILABLE_AFTER_UNLOCK)

        listingCallbackCaptor.value.onServicesUpdated(listOf(serviceInfo))
        testableLooper.processAllMessages()

        tile.click()
        testableLooper.processAllMessages()

        verify(controlsDialog, never()).show(any(ControlsUiController::class.java))
    }

    @Test
    fun testDialogDismissedOnDestroy() {
        verify(controlsListingController).observe(
                any(LifecycleOwner::class.java),
                capture(listingCallbackCaptor)
        )

        listingCallbackCaptor.value.onServicesUpdated(listOf(serviceInfo))
        testableLooper.processAllMessages()

        tile.click()
        testableLooper.processAllMessages()

        tile.destroy()
        testableLooper.processAllMessages()
        verify(controlsDialog).dismiss()
    }

    private fun createTile(): DeviceControlsTile {
        return DeviceControlsTile(
                qsHost,
                testableLooper.looper,
                Handler(testableLooper.looper),
                FalsingManagerFake(),
                metricsLogger,
                statusBarStateController,
                activityStarter,
                qsLogger,
                controlsComponent,
                featureFlags,
                { controlsDialog },
                globalSettings
        )
    }
}
