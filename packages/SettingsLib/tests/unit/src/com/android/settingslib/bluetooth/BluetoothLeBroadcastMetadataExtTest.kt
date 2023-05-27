/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.settingslib.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothLeAudioCodecConfigMetadata
import android.bluetooth.BluetoothLeAudioContentMetadata
import android.bluetooth.BluetoothLeBroadcastChannel
import android.bluetooth.BluetoothLeBroadcastMetadata
import android.bluetooth.BluetoothLeBroadcastSubgroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.bluetooth.BluetoothLeBroadcastMetadataExt.toQrCodeString
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BluetoothLeBroadcastMetadataExtTest {

    @Test
    fun toQrCodeString() {
        val subgroup = BluetoothLeBroadcastSubgroup.Builder().apply {
            setCodecId(100)
            val audioCodecConfigMetadata = BluetoothLeAudioCodecConfigMetadata.Builder().build()
            setCodecSpecificConfig(audioCodecConfigMetadata)
            setContentMetadata(BluetoothLeAudioContentMetadata.Builder().build())
            addChannel(BluetoothLeBroadcastChannel.Builder().apply {
                setChannelIndex(1000)
                setCodecMetadata(audioCodecConfigMetadata)
            }.build())
        }.build()

        val metadata = BluetoothLeBroadcastMetadata.Builder().apply {
            setSourceDevice(Device, 0)
            setSourceAdvertisingSid(1)
            setBroadcastId(2)
            setPaSyncInterval(3)
            setEncrypted(true)
            setBroadcastCode(byteArrayOf(10, 11, 12, 13))
            setPresentationDelayMicros(4)
            addSubgroup(subgroup)
        }.build()

        val qrCodeString = metadata.toQrCodeString()

        assertThat(qrCodeString).isEqualTo(QR_CODE_STRING)
    }

    @Test
    fun decodeAndEncodeAgain_sameString() {
        val metadata = BluetoothLeBroadcastMetadataExt.convertToBroadcastMetadata(QR_CODE_STRING)!!

        val qrCodeString = metadata.toQrCodeString()

        assertThat(qrCodeString).isEqualTo(QR_CODE_STRING)
    }

    private companion object {
        const val TEST_DEVICE_ADDRESS = "00:A1:A1:A1:A1:A1"

        val Device: BluetoothDevice =
            BluetoothAdapter.getDefaultAdapter().getRemoteDevice(TEST_DEVICE_ADDRESS)

        const val QR_CODE_STRING =
            "BT:BluetoothLeBroadcastMetadata:AAAAAAEAAAABAAAAEQAAADAAMAA6AEEAMQA6AEEAMQA6AEEAMQA6" +
                "AEEAMQA6AEEAMQAAAAAAAAABAAAAAgAAAAMAAAABAAAABAAAAAQAAAAKCwwNBAAAAAEAAAABAAAAZAAA" +
                "AAAAAAABAAAAAAAAAAAAAAAGAAAABgAAAAUDAAAAAAAAAAAAAAAAAAAAAAAAAQAAAP//////////AAAA" +
                "AAAAAAABAAAAAQAAAAAAAADoAwAAAQAAAAAAAAAAAAAABgAAAAYAAAAFAwAAAAAAAAAAAAAAAAAAAAAA" +
                "AAAAAAD/////AAAAAAAAAAA="
    }
}