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

package com.android.systemui.bluetooth.qsdialog

import android.bluetooth.BluetoothAdapter
import android.platform.test.annotations.EnableFlags
import android.testing.TestableLooper
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.settingslib.bluetooth.LocalBluetoothManager
import com.android.settingslib.flags.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.animation.Expandable
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.util.FakeSharedPreferences
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.kotlin.getMutableStateFlow
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@EnableFlags(Flags.FLAG_BLUETOOTH_QS_TILE_DIALOG_AUTO_ON_TOGGLE)
class BluetoothTileDialogViewModelTest : SysuiTestCase() {

    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()
    private val fakeSystemClock = FakeSystemClock()
    private val backgroundExecutor = FakeExecutor(fakeSystemClock)

    private lateinit var bluetoothTileDialogViewModel: BluetoothTileDialogViewModel

    @Mock private lateinit var bluetoothStateInteractor: BluetoothStateInteractor

    @Mock private lateinit var audioSharingInteractor: AudioSharingInteractor

    @Mock private lateinit var bluetoothDeviceMetadataInteractor: BluetoothDeviceMetadataInteractor

    @Mock private lateinit var deviceItemInteractor: DeviceItemInteractor

    @Mock private lateinit var deviceItemActionInteractor: DeviceItemActionInteractor

    @Mock private lateinit var activityStarter: ActivityStarter

    @Mock private lateinit var mDialogTransitionAnimator: DialogTransitionAnimator

    @Mock private lateinit var cachedBluetoothDevice: CachedBluetoothDevice

    @Mock private lateinit var deviceItem: DeviceItem

    @Mock private lateinit var uiEventLogger: UiEventLogger

    @Mock private lateinit var bluetoothAdapter: BluetoothAdapter

    @Mock private lateinit var localBluetoothManager: LocalBluetoothManager

    @Mock private lateinit var bluetoothTileDialogLogger: BluetoothTileDialogLogger

    @Mock
    private lateinit var mBluetoothTileDialogDelegateDelegateFactory:
        BluetoothTileDialogDelegate.Factory

    @Mock private lateinit var bluetoothTileDialogDelegate: BluetoothTileDialogDelegate

    @Mock private lateinit var sysuiDialog: SystemUIDialog
    @Mock private lateinit var expandable: Expandable
    @Mock private lateinit var controller: DialogTransitionAnimator.Controller

    private val sharedPreferences = FakeSharedPreferences()

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: CoroutineDispatcher
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        dispatcher = UnconfinedTestDispatcher(scheduler)
        testScope = TestScope(dispatcher)
        bluetoothTileDialogViewModel =
            BluetoothTileDialogViewModel(
                deviceItemInteractor,
                deviceItemActionInteractor,
                BluetoothStateInteractor(
                    localBluetoothManager,
                    bluetoothTileDialogLogger,
                    testScope.backgroundScope,
                    dispatcher
                ),
                // TODO(b/316822488): Create FakeBluetoothAutoOnInteractor.
                BluetoothAutoOnInteractor(
                    BluetoothAutoOnRepository(
                        localBluetoothManager,
                        bluetoothAdapter,
                        testScope.backgroundScope,
                        dispatcher
                    )
                ),
                audioSharingInteractor,
                bluetoothDeviceMetadataInteractor,
                mDialogTransitionAnimator,
                activityStarter,
                uiEventLogger,
                testScope.backgroundScope,
                dispatcher,
                dispatcher,
                sharedPreferences,
                mBluetoothTileDialogDelegateDelegateFactory
            )
        whenever(deviceItemInteractor.deviceItemUpdate).thenReturn(MutableSharedFlow())
        whenever(deviceItemInteractor.deviceItemUpdateRequest)
            .thenReturn(MutableStateFlow(Unit).asStateFlow())
        whenever(deviceItemInteractor.showSeeAllUpdate).thenReturn(getMutableStateFlow(false))
        whenever(bluetoothDeviceMetadataInteractor.metadataUpdate).thenReturn(MutableSharedFlow())
        whenever(mBluetoothTileDialogDelegateDelegateFactory.create(any(), anyInt(), any(), any()))
            .thenReturn(bluetoothTileDialogDelegate)
        whenever(bluetoothTileDialogDelegate.createDialog()).thenReturn(sysuiDialog)
        whenever(sysuiDialog.context).thenReturn(mContext)
        whenever(bluetoothTileDialogDelegate.bluetoothStateToggle)
            .thenReturn(getMutableStateFlow(false))
        whenever(bluetoothTileDialogDelegate.deviceItemClick)
            .thenReturn(getMutableStateFlow(deviceItem))
        whenever(bluetoothTileDialogDelegate.contentHeight).thenReturn(getMutableStateFlow(0))
        whenever(bluetoothTileDialogDelegate.bluetoothAutoOnToggle)
            .thenReturn(getMutableStateFlow(false))
        whenever(audioSharingInteractor.audioSharingButtonStateUpdate)
            .thenReturn(getMutableStateFlow(AudioSharingButtonState.Gone))
        whenever(expandable.dialogTransitionController(any())).thenReturn(controller)
    }

    @Test
    fun testShowDialog_noAnimation() {
        testScope.runTest {
            bluetoothTileDialogViewModel.showDialog(null)

            verify(mDialogTransitionAnimator, never()).show(any(), any(), any())
        }
    }

    @Test
    fun testShowDialog_animated() {
        testScope.runTest {
            bluetoothTileDialogViewModel.showDialog(expandable)

            verify(mDialogTransitionAnimator).show(any(), any(), anyBoolean())
        }
    }

    @Test
    fun testShowDialog_animated_callInBackgroundThread() {
        testScope.runTest {
            backgroundExecutor.execute {
                bluetoothTileDialogViewModel.showDialog(expandable)

                verify(mDialogTransitionAnimator).show(any(), any(), anyBoolean())
            }
        }
    }

    @Test
    fun testShowDialog_fetchDeviceItem() {
        testScope.runTest {
            bluetoothTileDialogViewModel.showDialog(null)

            verify(deviceItemInteractor).deviceItemUpdate
        }
    }

    @Test
    fun testStartSettingsActivity_activityLaunched_dialogDismissed() {
        testScope.runTest {
            whenever(deviceItem.cachedBluetoothDevice).thenReturn(cachedBluetoothDevice)
            bluetoothTileDialogViewModel.showDialog(null)

            val clickedView = View(context)
            bluetoothTileDialogViewModel.onPairNewDeviceClicked(clickedView)

            verify(uiEventLogger).log(BluetoothTileDialogUiEvent.PAIR_NEW_DEVICE_CLICKED)
            verify(activityStarter).postStartActivityDismissingKeyguard(any(), anyInt(), nullable())
        }
    }

    @Test
    fun testBuildUiProperties_bluetoothOn_shouldHideAutoOn() {
        testScope.runTest {
            val actual =
                BluetoothTileDialogViewModel.UiProperties.build(
                    isBluetoothEnabled = true,
                    isAutoOnToggleFeatureAvailable = true
                )
            assertThat(actual.autoOnToggleVisibility).isEqualTo(GONE)
        }
    }

    @Test
    fun testBuildUiProperties_bluetoothOff_shouldShowAutoOn() {
        testScope.runTest {
            val actual =
                BluetoothTileDialogViewModel.UiProperties.build(
                    isBluetoothEnabled = false,
                    isAutoOnToggleFeatureAvailable = true
                )
            assertThat(actual.autoOnToggleVisibility).isEqualTo(VISIBLE)
        }
    }

    @Test
    fun testBuildUiProperties_bluetoothOff_autoOnFeatureUnavailable_shouldHideAutoOn() {
        testScope.runTest {
            val actual =
                BluetoothTileDialogViewModel.UiProperties.build(
                    isBluetoothEnabled = false,
                    isAutoOnToggleFeatureAvailable = false
                )
            assertThat(actual.autoOnToggleVisibility).isEqualTo(GONE)
        }
    }

    @Test
    fun testIsAutoOnToggleFeatureAvailable_returnTrue() {
        testScope.runTest {
            whenever(bluetoothAdapter.isAutoOnSupported).thenReturn(true)

            val actual = bluetoothTileDialogViewModel.isAutoOnToggleFeatureAvailable()
            assertThat(actual).isTrue()
        }
    }

    @Test
    fun testIsAutoOnToggleFeatureAvailable_returnFalse() {
        testScope.runTest {
            whenever(bluetoothAdapter.isAutoOnSupported).thenReturn(false)

            val actual = bluetoothTileDialogViewModel.isAutoOnToggleFeatureAvailable()
            assertThat(actual).isFalse()
        }
    }
}
