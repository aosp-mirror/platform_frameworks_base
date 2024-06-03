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

import android.graphics.drawable.Drawable
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.LayoutInflater
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.filters.SmallTest
import com.android.internal.logging.UiEventLogger
import com.android.settingslib.bluetooth.CachedBluetoothDevice
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.DialogTransitionAnimator
import com.android.systemui.model.SysUiState
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.phone.SystemUIDialogManager
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mock
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
class BluetoothTileDialogDelegateTest : SysuiTestCase() {
    companion object {
        const val DEVICE_NAME = "device"
        const val DEVICE_CONNECTION_SUMMARY = "active"
        const val ENABLED = true
        const val CONTENT_HEIGHT = WRAP_CONTENT
    }

    @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var cachedBluetoothDevice: CachedBluetoothDevice

    @Mock private lateinit var bluetoothTileDialogCallback: BluetoothTileDialogCallback

    @Mock private lateinit var drawable: Drawable

    @Mock private lateinit var uiEventLogger: UiEventLogger

    @Mock private lateinit var logger: BluetoothTileDialogLogger

    private val uiProperties =
        BluetoothTileDialogViewModel.UiProperties.build(
            isBluetoothEnabled = ENABLED,
            isAutoOnToggleFeatureAvailable = ENABLED
        )
    @Mock private lateinit var sysuiDialogFactory: SystemUIDialog.Factory
    @Mock private lateinit var dialogManager: SystemUIDialogManager
    @Mock private lateinit var sysuiState: SysUiState
    @Mock private lateinit var dialogTransitionAnimator: DialogTransitionAnimator

    private val fakeSystemClock = FakeSystemClock()

    private lateinit var scheduler: TestCoroutineScheduler
    private lateinit var dispatcher: CoroutineDispatcher
    private lateinit var testScope: TestScope
    private lateinit var icon: Pair<Drawable, String>
    private lateinit var mBluetoothTileDialogDelegate: BluetoothTileDialogDelegate
    private lateinit var deviceItem: DeviceItem

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        dispatcher = UnconfinedTestDispatcher(scheduler)
        testScope = TestScope(dispatcher)

        whenever(sysuiState.setFlag(anyLong(), anyBoolean())).thenReturn(sysuiState)

        mBluetoothTileDialogDelegate =
            BluetoothTileDialogDelegate(
                uiProperties,
                CONTENT_HEIGHT,
                bluetoothTileDialogCallback,
                {},
                dispatcher,
                fakeSystemClock,
                uiEventLogger,
                logger,
                sysuiDialogFactory
            )

        whenever(sysuiDialogFactory.create(any(SystemUIDialog.Delegate::class.java))).thenAnswer {
            SystemUIDialog(
                mContext,
                0,
                SystemUIDialog.DEFAULT_DISMISS_ON_DEVICE_LOCK,
                dialogManager,
                sysuiState,
                fakeBroadcastDispatcher,
                dialogTransitionAnimator,
                it.getArgument(0)
            )
        }

        icon = Pair(drawable, DEVICE_NAME)
        deviceItem =
            DeviceItem(
                type = DeviceItemType.AVAILABLE_MEDIA_BLUETOOTH_DEVICE,
                cachedBluetoothDevice = cachedBluetoothDevice,
                deviceName = DEVICE_NAME,
                connectionSummary = DEVICE_CONNECTION_SUMMARY,
                iconWithDescription = icon,
                background = null
            )
        `when`(cachedBluetoothDevice.isBusy).thenReturn(false)
    }

    @Test
    fun testShowDialog_createRecyclerViewWithAdapter() {
        val dialog = mBluetoothTileDialogDelegate.createDialog()
        dialog.show()

        val recyclerView = dialog.requireViewById<RecyclerView>(R.id.device_list)

        assertThat(recyclerView).isNotNull()
        assertThat(recyclerView.visibility).isEqualTo(VISIBLE)
        assertThat(recyclerView.adapter).isNotNull()
        assertThat(recyclerView.layoutManager is LinearLayoutManager).isTrue()
        dialog.dismiss()
    }

    @Test
    fun testShowDialog_displayBluetoothDevice() {
        testScope.runTest {
            val dialog = mBluetoothTileDialogDelegate.createDialog()
            dialog.show()
            fakeSystemClock.setElapsedRealtime(Long.MAX_VALUE)
            mBluetoothTileDialogDelegate.onDeviceItemUpdated(
                dialog,
                listOf(deviceItem),
                showSeeAll = false,
                showPairNewDevice = false
            )

            val recyclerView = dialog.requireViewById<RecyclerView>(R.id.device_list)
            val adapter = recyclerView?.adapter as BluetoothTileDialogDelegate.Adapter
            assertThat(adapter.itemCount).isEqualTo(1)
            assertThat(adapter.getItem(0).deviceName).isEqualTo(DEVICE_NAME)
            assertThat(adapter.getItem(0).connectionSummary).isEqualTo(DEVICE_CONNECTION_SUMMARY)
            assertThat(adapter.getItem(0).iconWithDescription).isEqualTo(icon)
            dialog.dismiss()
        }
    }

    @Test
    fun testDeviceItemViewHolder_cachedDeviceNotBusy() {
        deviceItem.isEnabled = true

        val view =
            LayoutInflater.from(mContext).inflate(R.layout.bluetooth_device_item, null, false)
        val viewHolder =
            mBluetoothTileDialogDelegate
                .Adapter(bluetoothTileDialogCallback)
                .DeviceItemViewHolder(view)
        viewHolder.bind(deviceItem, bluetoothTileDialogCallback)
        val container = view.requireViewById<View>(R.id.bluetooth_device_row)

        assertThat(container).isNotNull()
        assertThat(container.isEnabled).isTrue()
        assertThat(container.hasOnClickListeners()).isTrue()
    }

    @Test
    fun testDeviceItemViewHolder_cachedDeviceBusy() {
        deviceItem.isEnabled = false

        val view =
            LayoutInflater.from(mContext).inflate(R.layout.bluetooth_device_item, null, false)
        val viewHolder =
            BluetoothTileDialogDelegate(
                    uiProperties,
                    CONTENT_HEIGHT,
                    bluetoothTileDialogCallback,
                    {},
                    dispatcher,
                    fakeSystemClock,
                    uiEventLogger,
                    logger,
                    sysuiDialogFactory,
                )
                .Adapter(bluetoothTileDialogCallback)
                .DeviceItemViewHolder(view)
        viewHolder.bind(deviceItem, bluetoothTileDialogCallback)
        val container = view.requireViewById<View>(R.id.bluetooth_device_row)

        assertThat(container).isNotNull()
        assertThat(container.isEnabled).isFalse()
        assertThat(container.hasOnClickListeners()).isTrue()
    }

    @Test
    fun testOnDeviceUpdated_hideSeeAll_showPairNew() {
        testScope.runTest {
            val dialog = mBluetoothTileDialogDelegate.createDialog()
            dialog.show()
            fakeSystemClock.setElapsedRealtime(Long.MAX_VALUE)
            mBluetoothTileDialogDelegate.onDeviceItemUpdated(
                dialog,
                listOf(deviceItem),
                showSeeAll = false,
                showPairNewDevice = true
            )

            val seeAllButton = dialog.requireViewById<View>(R.id.see_all_button)
            val pairNewButton = dialog.requireViewById<View>(R.id.pair_new_device_button)
            val recyclerView = dialog.requireViewById<RecyclerView>(R.id.device_list)
            val adapter = recyclerView?.adapter as BluetoothTileDialogDelegate.Adapter
            val scrollViewContent = dialog.requireViewById<View>(R.id.scroll_view)

            assertThat(seeAllButton).isNotNull()
            assertThat(seeAllButton.visibility).isEqualTo(GONE)
            assertThat(pairNewButton).isNotNull()
            assertThat(pairNewButton.visibility).isEqualTo(VISIBLE)
            assertThat(adapter.itemCount).isEqualTo(1)
            assertThat(scrollViewContent.layoutParams.height).isEqualTo(WRAP_CONTENT)
            dialog.dismiss()
        }
    }

    @Test
    fun testShowDialog_cachedHeightLargerThanMinHeight_displayFromCachedHeight() {
        testScope.runTest {
            val cachedHeight = Int.MAX_VALUE
            val dialog =
                BluetoothTileDialogDelegate(
                        BluetoothTileDialogViewModel.UiProperties.build(ENABLED, ENABLED),
                        cachedHeight,
                        bluetoothTileDialogCallback,
                        {},
                        dispatcher,
                        fakeSystemClock,
                        uiEventLogger,
                        logger,
                        sysuiDialogFactory,
                    )
                    .createDialog()
            dialog.show()
            assertThat(dialog.requireViewById<View>(R.id.scroll_view).layoutParams.height)
                .isEqualTo(cachedHeight)
            dialog.dismiss()
        }
    }

    @Test
    fun testShowDialog_cachedHeightLessThanMinHeight_displayFromUiProperties() {
        testScope.runTest {
            val dialog =
                BluetoothTileDialogDelegate(
                        BluetoothTileDialogViewModel.UiProperties.build(ENABLED, ENABLED),
                        MATCH_PARENT,
                        bluetoothTileDialogCallback,
                        {},
                        dispatcher,
                        fakeSystemClock,
                        uiEventLogger,
                        logger,
                        sysuiDialogFactory,
                    )
                    .createDialog()
            dialog.show()
            assertThat(dialog.requireViewById<View>(R.id.scroll_view).layoutParams.height)
                .isGreaterThan(MATCH_PARENT)
            dialog.dismiss()
        }
    }

    @Test
    fun testShowDialog_bluetoothEnabled_autoOnToggleGone() {
        testScope.runTest {
            val dialog =
                BluetoothTileDialogDelegate(
                        BluetoothTileDialogViewModel.UiProperties.build(ENABLED, ENABLED),
                        MATCH_PARENT,
                        bluetoothTileDialogCallback,
                        {},
                        dispatcher,
                        fakeSystemClock,
                        uiEventLogger,
                        logger,
                        sysuiDialogFactory,
                    )
                    .createDialog()
            dialog.show()
            assertThat(
                    dialog.requireViewById<View>(R.id.bluetooth_auto_on_toggle_layout).visibility
                )
                .isEqualTo(GONE)
            dialog.dismiss()
        }
    }
}
