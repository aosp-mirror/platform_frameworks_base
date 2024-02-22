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
    fun toQrCodeString_encrypted() {
        val subgroup = BluetoothLeBroadcastSubgroup.Builder().apply {
            setCodecId(0x6)
            val audioCodecConfigMetadata = BluetoothLeAudioCodecConfigMetadata.Builder().build()
            setCodecSpecificConfig(audioCodecConfigMetadata)
            setContentMetadata(BluetoothLeAudioContentMetadata.Builder()
                    .setProgramInfo("Test").setLanguage("eng").build())
            addChannel(BluetoothLeBroadcastChannel.Builder().apply {
                setSelected(true)
                setChannelIndex(2)
                setCodecMetadata(audioCodecConfigMetadata)
            }.build())
            addChannel(BluetoothLeBroadcastChannel.Builder().apply {
                setSelected(true)
                setChannelIndex(1)
                setCodecMetadata(audioCodecConfigMetadata)
            }.build())
        }.build()

        val metadata = BluetoothLeBroadcastMetadata.Builder().apply {
            setSourceDevice(Device, BluetoothDevice.ADDRESS_TYPE_RANDOM)
            setSourceAdvertisingSid(1)
            setBroadcastId(123456)
            setBroadcastName("Test")
            setPublicBroadcastMetadata(BluetoothLeAudioContentMetadata.Builder()
                    .setProgramInfo("pTest").build())
            setPaSyncInterval(160)
            setEncrypted(true)
            setBroadcastCode("TestCode".toByteArray(Charsets.UTF_8))
            addSubgroup(subgroup)
        }.build()

        val qrCodeString = metadata.toQrCodeString()

        assertThat(qrCodeString).isEqualTo(QR_CODE_STRING)
    }

    @Test
    fun toQrCodeString_non_encrypted() {
        val subgroup = BluetoothLeBroadcastSubgroup.Builder().apply {
            setCodecId(0x6)
            val audioCodecConfigMetadata = BluetoothLeAudioCodecConfigMetadata.Builder().build()
            setContentMetadata(BluetoothLeAudioContentMetadata.Builder()
                .build())
            setCodecSpecificConfig(audioCodecConfigMetadata)
            addChannel(BluetoothLeBroadcastChannel.Builder().apply {
                setSelected(true)
                setChannelIndex(1)
                setCodecMetadata(audioCodecConfigMetadata)
            }.build())
        }.build()

        val metadata = BluetoothLeBroadcastMetadata.Builder().apply {
            setSourceDevice(DevicePublic, BluetoothDevice.ADDRESS_TYPE_PUBLIC)
            setSourceAdvertisingSid(1)
            setBroadcastId(0xDE51E9)
            setBroadcastName("Hockey")
            setAudioConfigQuality(BluetoothLeBroadcastMetadata.AUDIO_CONFIG_QUALITY_STANDARD)
            setPaSyncInterval(0xFFFF)
            setEncrypted(false)
            addSubgroup(subgroup)
        }.build()

        val qrCodeString = metadata.toQrCodeString()

        assertThat(qrCodeString).isEqualTo(QR_CODE_STRING_NON_ENCRYPTED)
    }

    @Test
    fun toQrCodeString_NoChannelSelected() {
        val subgroup = BluetoothLeBroadcastSubgroup.Builder().apply {
            setCodecId(0x6)
            val audioCodecConfigMetadata = BluetoothLeAudioCodecConfigMetadata.Builder().build()
            setCodecSpecificConfig(audioCodecConfigMetadata)
            setContentMetadata(BluetoothLeAudioContentMetadata.Builder()
                .setProgramInfo("Test").setLanguage("eng").build())
            addChannel(BluetoothLeBroadcastChannel.Builder().apply {
                setSelected(false)
                setChannelIndex(2)
                setCodecMetadata(audioCodecConfigMetadata)
            }.build())
            addChannel(BluetoothLeBroadcastChannel.Builder().apply {
                setSelected(false)
                setChannelIndex(1)
                setCodecMetadata(audioCodecConfigMetadata)
            }.build())
        }.build()

        val metadata = BluetoothLeBroadcastMetadata.Builder().apply {
            setSourceDevice(Device, BluetoothDevice.ADDRESS_TYPE_RANDOM)
            setSourceAdvertisingSid(1)
            setBroadcastId(123456)
            setBroadcastName("Test")
            setPublicBroadcastMetadata(BluetoothLeAudioContentMetadata.Builder()
                .setProgramInfo("pTest").build())
            setPaSyncInterval(160)
            setEncrypted(true)
            setBroadcastCode("TestCode".toByteArray(Charsets.UTF_8))
            addSubgroup(subgroup)
        }.build()

        // if no channel is selected, no preference(0xFFFFFFFFu) will be set in BIS
        val qrCodeString = metadata.toQrCodeString()

        val parsedMetadata =
            BluetoothLeBroadcastMetadataExt.convertToBroadcastMetadata(qrCodeString)!!

        assertThat(parsedMetadata).isNotNull()
        assertThat(parsedMetadata.subgroups).isNotNull()
        assertThat(parsedMetadata.subgroups.size).isEqualTo(1)
        assertThat(parsedMetadata.subgroups[0].channels).isNotNull()
        assertThat(parsedMetadata.subgroups[0].channels.size).isEqualTo(1)
        assertThat(parsedMetadata.subgroups[0].hasChannelPreference()).isFalse()
        // placeholder channel with not selected
        assertThat(parsedMetadata.subgroups[0].channels[0].channelIndex).isEqualTo(1)
        assertThat(parsedMetadata.subgroups[0].channels[0].isSelected).isFalse()
    }

    @Test
    fun toQrCodeString_OneChannelSelected() {
        val subgroup = BluetoothLeBroadcastSubgroup.Builder().apply {
            setCodecId(0x6)
            val audioCodecConfigMetadata = BluetoothLeAudioCodecConfigMetadata.Builder().build()
            setCodecSpecificConfig(audioCodecConfigMetadata)
            setContentMetadata(BluetoothLeAudioContentMetadata.Builder()
                .setProgramInfo("Test").setLanguage("eng").build())
            addChannel(BluetoothLeBroadcastChannel.Builder().apply {
                setSelected(false)
                setChannelIndex(1)
                setCodecMetadata(audioCodecConfigMetadata)
            }.build())
            addChannel(BluetoothLeBroadcastChannel.Builder().apply {
                setSelected(true)
                setChannelIndex(2)
                setCodecMetadata(audioCodecConfigMetadata)
            }.build())
        }.build()

        val metadata = BluetoothLeBroadcastMetadata.Builder().apply {
            setSourceDevice(Device, BluetoothDevice.ADDRESS_TYPE_RANDOM)
            setSourceAdvertisingSid(1)
            setBroadcastId(123456)
            setBroadcastName("Test")
            setPublicBroadcastMetadata(BluetoothLeAudioContentMetadata.Builder()
                .setProgramInfo("pTest").build())
            setPaSyncInterval(160)
            setEncrypted(true)
            setBroadcastCode("TestCode".toByteArray(Charsets.UTF_8))
            addSubgroup(subgroup)
        }.build()

        val qrCodeString = metadata.toQrCodeString()

        val parsedMetadata =
            BluetoothLeBroadcastMetadataExt.convertToBroadcastMetadata(qrCodeString)!!

        assertThat(parsedMetadata).isNotNull()
        assertThat(parsedMetadata.subgroups).isNotNull()
        assertThat(parsedMetadata.subgroups.size).isEqualTo(1)
        assertThat(parsedMetadata.subgroups[0].channels).isNotNull()
        // Only selected channel can be recovered, non-selected ones will be ignored
        assertThat(parsedMetadata.subgroups[0].channels.size).isEqualTo(1)
        assertThat(parsedMetadata.subgroups[0].hasChannelPreference()).isTrue()
        assertThat(parsedMetadata.subgroups[0].channels[0].channelIndex).isEqualTo(2)
        assertThat(parsedMetadata.subgroups[0].channels[0].isSelected).isTrue()
    }

    @Test
    fun decodeAndEncodeAgain_sameString() {
        val metadata = BluetoothLeBroadcastMetadataExt.convertToBroadcastMetadata(QR_CODE_STRING)!!

        val qrCodeString = metadata.toQrCodeString()

        assertThat(qrCodeString).isEqualTo(QR_CODE_STRING)
    }

    @Test
    fun decodeAndEncodeAgain_sameString_non_encrypted() {
        val metadata =
                BluetoothLeBroadcastMetadataExt
                        .convertToBroadcastMetadata(QR_CODE_STRING_NON_ENCRYPTED)!!

        val qrCodeString = metadata.toQrCodeString()

        assertThat(qrCodeString).isEqualTo(QR_CODE_STRING_NON_ENCRYPTED)
    }

    private companion object {
        const val TEST_DEVICE_ADDRESS = "00:A1:A1:A1:A1:A1"
        const val TEST_DEVICE_ADDRESS_PUBLIC = "AA:BB:CC:00:11:22"

        val Device: BluetoothDevice =
            BluetoothAdapter.getDefaultAdapter().getRemoteLeDevice(TEST_DEVICE_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM)

        val DevicePublic: BluetoothDevice =
            BluetoothAdapter.getDefaultAdapter().getRemoteLeDevice(TEST_DEVICE_ADDRESS_PUBLIC,
                BluetoothDevice.ADDRESS_TYPE_PUBLIC)

        const val QR_CODE_STRING =
            "BLUETOOTH:UUID:184F;BN:VGVzdA==;AT:1;AD:00A1A1A1A1A1;BI:1E240;BC:VGVzdENvZGU=;" +
            "MD:BgNwVGVzdA==;AS:1;PI:A0;NS:1;BS:3;NB:2;SM:BQNUZXN0BARlbmc=;;"
        const val QR_CODE_STRING_NON_ENCRYPTED =
            "BLUETOOTH:UUID:184F;BN:SG9ja2V5;AT:0;AD:AABBCC001122;BI:DE51E9;SQ:1;AS:1;PI:FFFF;" +
            "NS:1;BS:1;NB:1;;"
    }
}