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

package com.android.systemui.qs.tileimpl

import android.content.ComponentName
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.qs.QSHost
import com.android.systemui.qs.external.CustomTile
import com.android.systemui.qs.tiles.AirplaneModeTile
import com.android.systemui.qs.tiles.AlarmTile
import com.android.systemui.qs.tiles.BatterySaverTile
import com.android.systemui.qs.tiles.BluetoothTile
import com.android.systemui.qs.tiles.CameraToggleTile
import com.android.systemui.qs.tiles.CastTile
import com.android.systemui.qs.tiles.ColorCorrectionTile
import com.android.systemui.qs.tiles.ColorInversionTile
import com.android.systemui.qs.tiles.DataSaverTile
import com.android.systemui.qs.tiles.DeviceControlsTile
import com.android.systemui.qs.tiles.DndTile
import com.android.systemui.qs.tiles.DreamTile
import com.android.systemui.qs.tiles.FlashlightTile
import com.android.systemui.qs.tiles.FontScalingTile
import com.android.systemui.qs.tiles.HotspotTile
import com.android.systemui.qs.tiles.InternetTile
import com.android.systemui.qs.tiles.LocationTile
import com.android.systemui.qs.tiles.MicrophoneToggleTile
import com.android.systemui.qs.tiles.NfcTile
import com.android.systemui.qs.tiles.NightDisplayTile
import com.android.systemui.qs.tiles.OneHandedModeTile
import com.android.systemui.qs.tiles.QRCodeScannerTile
import com.android.systemui.qs.tiles.QuickAccessWalletTile
import com.android.systemui.qs.tiles.ReduceBrightColorsTile
import com.android.systemui.qs.tiles.RotationLockTile
import com.android.systemui.qs.tiles.ScreenRecordTile
import com.android.systemui.qs.tiles.UiModeNightTile
import com.android.systemui.qs.tiles.WorkModeTile
import com.android.systemui.util.mockito.any
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito.inOrder
import org.mockito.MockitoAnnotations
import javax.inject.Provider
import org.mockito.Mockito.`when` as whenever

private val specMap = mapOf(
        "internet" to InternetTile::class.java,
        "bt" to BluetoothTile::class.java,
        "dnd" to DndTile::class.java,
        "inversion" to ColorInversionTile::class.java,
        "airplane" to AirplaneModeTile::class.java,
        "work" to WorkModeTile::class.java,
        "rotation" to RotationLockTile::class.java,
        "flashlight" to FlashlightTile::class.java,
        "location" to LocationTile::class.java,
        "cast" to CastTile::class.java,
        "hotspot" to HotspotTile::class.java,
        "battery" to BatterySaverTile::class.java,
        "saver" to DataSaverTile::class.java,
        "night" to NightDisplayTile::class.java,
        "nfc" to NfcTile::class.java,
        "dark" to UiModeNightTile::class.java,
        "screenrecord" to ScreenRecordTile::class.java,
        "reduce_brightness" to ReduceBrightColorsTile::class.java,
        "cameratoggle" to CameraToggleTile::class.java,
        "mictoggle" to MicrophoneToggleTile::class.java,
        "controls" to DeviceControlsTile::class.java,
        "alarm" to AlarmTile::class.java,
        "wallet" to QuickAccessWalletTile::class.java,
        "qr_code_scanner" to QRCodeScannerTile::class.java,
        "onehanded" to OneHandedModeTile::class.java,
        "color_correction" to ColorCorrectionTile::class.java,
        "dream" to DreamTile::class.java,
        "font_scaling" to FontScalingTile::class.java
)

@RunWith(AndroidTestingRunner::class)
@SmallTest
class QSFactoryImplTest : SysuiTestCase() {

    @Mock private lateinit var qsHost: QSHost
    @Mock private lateinit var customTileFactory: CustomTile.Factory
    @Mock private lateinit var customTile: CustomTile

    @Mock private lateinit var internetTile: InternetTile
    @Mock private lateinit var bluetoothTile: BluetoothTile
    @Mock private lateinit var dndTile: DndTile
    @Mock private lateinit var colorInversionTile: ColorInversionTile
    @Mock private lateinit var airplaneTile: AirplaneModeTile
    @Mock private lateinit var workTile: WorkModeTile
    @Mock private lateinit var rotationTile: RotationLockTile
    @Mock private lateinit var flashlightTile: FlashlightTile
    @Mock private lateinit var locationTile: LocationTile
    @Mock private lateinit var castTile: CastTile
    @Mock private lateinit var hotspotTile: HotspotTile
    @Mock private lateinit var batterySaverTile: BatterySaverTile
    @Mock private lateinit var dataSaverTile: DataSaverTile
    @Mock private lateinit var nightDisplayTile: NightDisplayTile
    @Mock private lateinit var nfcTile: NfcTile
    @Mock private lateinit var darkModeTile: UiModeNightTile
    @Mock private lateinit var screenRecordTile: ScreenRecordTile
    @Mock private lateinit var reduceBrightColorsTile: ReduceBrightColorsTile
    @Mock private lateinit var cameraToggleTile: CameraToggleTile
    @Mock private lateinit var microphoneToggleTile: MicrophoneToggleTile
    @Mock private lateinit var deviceControlsTile: DeviceControlsTile
    @Mock private lateinit var alarmTile: AlarmTile
    @Mock private lateinit var quickAccessWalletTile: QuickAccessWalletTile
    @Mock private lateinit var qrCodeScannerTile: QRCodeScannerTile
    @Mock private lateinit var oneHandedModeTile: OneHandedModeTile
    @Mock private lateinit var colorCorrectionTile: ColorCorrectionTile
    @Mock private lateinit var dreamTile: DreamTile
    @Mock private lateinit var fontScalingTile: FontScalingTile

    private lateinit var factory: QSFactoryImpl

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(qsHost.context).thenReturn(mContext)
        whenever(qsHost.userContext).thenReturn(mContext)
        whenever(customTileFactory.create(anyString(), any())).thenReturn(customTile)

        val tileMap = mutableMapOf<String, Provider<QSTileImpl<*>>>(
            "internet" to Provider { internetTile },
            "bt" to Provider { bluetoothTile },
            "dnd" to Provider { dndTile },
            "inversion" to Provider { colorInversionTile },
            "airplane" to Provider { airplaneTile },
            "work" to Provider { workTile },
            "rotation" to Provider { rotationTile },
            "flashlight" to Provider { flashlightTile },
            "location" to Provider { locationTile },
            "cast" to Provider { castTile },
            "hotspot" to Provider { hotspotTile },
            "battery" to Provider { batterySaverTile },
            "saver" to Provider { dataSaverTile },
            "night" to Provider { nightDisplayTile },
            "nfc" to Provider { nfcTile },
            "dark" to Provider { darkModeTile },
            "screenrecord" to Provider { screenRecordTile },
            "reduce_brightness" to Provider { reduceBrightColorsTile },
            "cameratoggle" to Provider { cameraToggleTile },
            "mictoggle" to Provider { microphoneToggleTile },
            "controls" to Provider { deviceControlsTile },
            "alarm" to Provider { alarmTile },
            "wallet" to Provider { quickAccessWalletTile },
            "qr_code_scanner" to Provider { qrCodeScannerTile },
            "onehanded" to Provider { oneHandedModeTile },
            "color_correction" to Provider { colorCorrectionTile },
            "dream" to Provider { dreamTile },
            "font_scaling" to Provider { fontScalingTile }
        )

        factory = QSFactoryImpl(
                { qsHost },
                { customTileFactory },
                tileMap,
        )
        // When adding/removing tiles, fix also [specMap] and [tileMap]
    }

    @Test
    fun testCorrectTileClassStock() {
        specMap.forEach { spec, klazz ->
            assertThat(factory.createTile(spec)).isInstanceOf(klazz)
        }
    }

    @Test
    fun testCustomTileClass() {
        val customSpec = CustomTile.toSpec(ComponentName("test", "test"))
        assertThat(factory.createTile(customSpec)).isInstanceOf(CustomTile::class.java)
    }

    @Test
    fun testBadTileNull() {
        assertThat(factory.createTile("-432~")).isNull()
    }

    @Test
    fun testTileInitializedAndStale() {
        specMap.forEach { spec, _ ->
            val tile = factory.createTile(spec) as QSTileImpl<*>
            val inOrder = inOrder(tile)
            inOrder.verify(tile).initialize()
            inOrder.verify(tile).postStale()
        }

        val customSpec = CustomTile.toSpec(ComponentName("test", "test"))
        val tile = factory.createTile(customSpec) as QSTileImpl<*>
        val inOrder = inOrder(tile)
        inOrder.verify(tile).initialize()
        inOrder.verify(tile).postStale()
    }
}
