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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.provider.Settings
import android.service.quicksettings.Tile
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.lifecycle.LifecycleOwner
import androidx.test.filters.SmallTest
import com.android.internal.logging.MetricsLogger
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.controller.ControlInfo
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.controller.StructureInfo
import com.android.systemui.controls.dagger.ControlsComponent
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.ui.ControlsActivity
import com.android.systemui.controls.ui.ControlsUiController
import com.android.systemui.controls.ui.SelectedItem
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.QsEventLogger
import com.android.systemui.qs.logging.QSLogger
import com.android.systemui.qs.tileimpl.QSTileImpl
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.settings.FakeSettings
import com.android.systemui.util.settings.SecureSettings
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.nullable
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.Mockito.`when`
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
    private lateinit var serviceInfo: ControlsServiceInfo
    @Mock
    private lateinit var uiEventLogger: QsEventLogger
    @Captor
    private lateinit var listingCallbackCaptor:
            ArgumentCaptor<ControlsListingController.ControlsListingCallback>
    @Captor
    private lateinit var intentCaptor: ArgumentCaptor<Intent>

    private lateinit var testableLooper: TestableLooper
    private lateinit var tile: DeviceControlsTile

    private lateinit var secureSettings: SecureSettings
    private lateinit var spiedContext: Context
    private var featureEnabled = true

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        secureSettings = FakeSettings()

        spiedContext = spy(mContext)
        doNothing().`when`(spiedContext).startActivity(any(Intent::class.java))
        `when`(qsHost.context).thenReturn(spiedContext)
        `when`(controlsComponent.isEnabled()).thenReturn(true)
        `when`(controlsController.getPreferredSelection())
                .thenReturn(SelectedItem.StructureItem(
                        StructureInfo(ComponentName("pkg", "cls"), "structure", listOf())))
        secureSettings.putInt(Settings.Secure.LOCKSCREEN_SHOW_CONTROLS, 1)

        setupControlsComponent()

        tile = createTile()
    }

    @After
    fun tearDown() {
        tile.destroy()
        testableLooper.processAllMessages()
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

        `when`(controlsComponent.getTileTitleId()).thenReturn(R.string.quick_controls_title)
        `when`(controlsComponent.getTileTitleId()).thenReturn(R.drawable.controls_icon)
    }

    @Test
    fun testAvailable() {
        assertThat(tile.isAvailable).isTrue()
    }

    @Test
    fun testNotAvailableControls() {
        featureEnabled = false

        // Destroy previous tile
        tile.destroy()
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
        assertThat(tile.longClickIntent).isNull()
    }

    @Test
    fun testDoesNotHandleLongClick() {
        assertThat(tile.state.handlesLongClick).isFalse()
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
    fun testStateActiveIfListingsHasControlsFavorited() {
        verify(controlsListingController).observe(
                any(LifecycleOwner::class.java),
                capture(listingCallbackCaptor)
        )
        `when`(controlsComponent.getVisibility()).thenReturn(ControlsComponent.Visibility.AVAILABLE)
        `when`(controlsController.getPreferredSelection()).thenReturn(
            SelectedItem.StructureItem(StructureInfo(
                ComponentName("pkg", "cls"),
                "structure",
                listOf(ControlInfo("id", "title", "subtitle", 1))
            ))
        )

        listingCallbackCaptor.value.onServicesUpdated(listOf(serviceInfo))
        testableLooper.processAllMessages()

        assertThat(tile.state.state).isEqualTo(Tile.STATE_ACTIVE)
    }

    @Test
    fun testStateInactiveIfListingsHasNoControlsFavorited() {
        verify(controlsListingController).observe(
                any(LifecycleOwner::class.java),
                capture(listingCallbackCaptor)
        )
        `when`(controlsComponent.getVisibility()).thenReturn(ControlsComponent.Visibility.AVAILABLE)
        `when`(controlsController.getPreferredSelection())
                .thenReturn(SelectedItem.StructureItem(
                        StructureInfo(ComponentName("pkg", "cls"), "structure", listOf())))

        listingCallbackCaptor.value.onServicesUpdated(listOf(serviceInfo))
        testableLooper.processAllMessages()

        assertThat(tile.state.state).isEqualTo(Tile.STATE_INACTIVE)
    }

    @Test
    fun testStateActiveIfPreferredIsPanel() {
        verify(controlsListingController).observe(
                any(LifecycleOwner::class.java),
                capture(listingCallbackCaptor)
        )
        `when`(controlsComponent.getVisibility()).thenReturn(ControlsComponent.Visibility.AVAILABLE)
        `when`(controlsController.getPreferredSelection())
                .thenReturn(SelectedItem.PanelItem("appName", ComponentName("pkg", "cls")))

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
    fun handleClick_unavailable_noActivityStarted() {
        tile.click(null /* view */)
        testableLooper.processAllMessages()

        verifyZeroInteractions(activityStarter)
    }

    @Test
    fun handleClick_available_shownOverLockscreenWhenLocked() {
        verify(controlsListingController).observe(
                any(LifecycleOwner::class.java),
                capture(listingCallbackCaptor)
        )
        `when`(controlsComponent.getVisibility()).thenReturn(ControlsComponent.Visibility.AVAILABLE)
        `when`(controlsUiController.resolveActivity()).thenReturn(ControlsActivity::class.java)
        `when`(controlsController.getPreferredSelection()).thenReturn(
            SelectedItem.StructureItem(StructureInfo(
                    ComponentName("pkg", "cls"),
                    "structure",
                    listOf(ControlInfo("id", "title", "subtitle", 1))
            ))
        )

        listingCallbackCaptor.value.onServicesUpdated(listOf(serviceInfo))
        testableLooper.processAllMessages()

        tile.click(null /* view */)
        testableLooper.processAllMessages()

        verify(activityStarter).startActivity(
                intentCaptor.capture(),
                eq(true) /* dismissShade */,
                nullable(ActivityLaunchAnimator.Controller::class.java),
                eq(true) /* showOverLockscreenWhenLocked */)
        assertThat(intentCaptor.value.component?.className).isEqualTo(CONTROLS_ACTIVITY_CLASS_NAME)
    }

    @Test
    fun handleClick_availableAfterUnlock_notShownOverLockscreenWhenLocked() {
        verify(controlsListingController).observe(
            any(LifecycleOwner::class.java),
            capture(listingCallbackCaptor)
        )
        `when`(controlsComponent.getVisibility())
            .thenReturn(ControlsComponent.Visibility.AVAILABLE_AFTER_UNLOCK)
        `when`(controlsUiController.resolveActivity()).thenReturn(ControlsActivity::class.java)
        `when`(controlsController.getPreferredSelection()).thenReturn(
            SelectedItem.StructureItem(StructureInfo(
                ComponentName("pkg", "cls"),
                "structure",
                listOf(ControlInfo("id", "title", "subtitle", 1))
            ))
        )

        listingCallbackCaptor.value.onServicesUpdated(listOf(serviceInfo))
        testableLooper.processAllMessages()

        tile.click(null /* view */)
        testableLooper.processAllMessages()

        verify(activityStarter).startActivity(
                intentCaptor.capture(),
                anyBoolean() /* dismissShade */,
                nullable(ActivityLaunchAnimator.Controller::class.java),
                eq(false) /* showOverLockscreenWhenLocked */)
        assertThat(intentCaptor.value.component?.className).isEqualTo(CONTROLS_ACTIVITY_CLASS_NAME)
    }

    @Test
    fun verifyTileEqualsResourceFromComponent() {
        assertThat(tile.tileLabel)
            .isEqualTo(
                context.getText(
                    controlsComponent.getTileTitleId()))
    }

    @Test
    fun verifyTileImageEqualsResourceFromComponent() {
        assertThat(tile.icon)
            .isEqualTo(QSTileImpl.ResourceIcon.get(controlsComponent.getTileImageId()))
    }

    private fun createTile(): DeviceControlsTile {
        return DeviceControlsTile(
                qsHost,
                uiEventLogger,
                testableLooper.looper,
                Handler(testableLooper.looper),
                FalsingManagerFake(),
                metricsLogger,
                statusBarStateController,
                activityStarter,
                qsLogger,
                controlsComponent
        ).also {
            it.initialize()
            testableLooper.processAllMessages()
        }
    }
}

private const val CONTROLS_ACTIVITY_CLASS_NAME = "com.android.systemui.controls.ui.ControlsActivity"
