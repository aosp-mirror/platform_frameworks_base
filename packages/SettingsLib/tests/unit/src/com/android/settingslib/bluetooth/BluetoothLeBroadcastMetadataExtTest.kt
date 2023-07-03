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

        val qrCodeString = metadata.toQrCodeString()

        val parsedMetadata =
            BluetoothLeBroadcastMetadataExt.convertToBroadcastMetadata(qrCodeString)!!

        assertThat(parsedMetadata).isNotNull()
        assertThat(parsedMetadata.subgroups).isNotNull()
        assertThat(parsedMetadata.subgroups.size).isEqualTo(1)
        assertThat(parsedMetadata.subgroups[0].channels).isNotNull()
        assertThat(parsedMetadata.subgroups[0].channels.size).isEqualTo(2)
        assertThat(parsedMetadata.subgroups[0].hasChannelPreference()).isFalse()
        // Input order does not matter due to parsing through bisMask
        assertThat(parsedMetadata.subgroups[0].channels[0].channelIndex).isEqualTo(1)
        assertThat(parsedMetadata.subgroups[0].channels[0].isSelected).isFalse()
        assertThat(parsedMetadata.subgroups[0].channels[1].channelIndex).isEqualTo(2)
        assertThat(parsedMetadata.subgroups[0].channels[1].isSelected).isFalse()
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
        // Only selected channel can be recovered
        assertThat(parsedMetadata.subgroups[0].channels.size).isEqualTo(2)
        assertThat(parsedMetadata.subgroups[0].hasChannelPreference()).isTrue()
        assertThat(parsedMetadata.subgroups[0].channels[0].channelIndex).isEqualTo(1)
        assertThat(parsedMetadata.subgroups[0].channels[0].isSelected).isFalse()
        assertThat(parsedMetadata.subgroups[0].channels[1].channelIndex).isEqualTo(2)
        assertThat(parsedMetadata.subgroups[0].channels[1].isSelected).isTrue()
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
            BluetoothAdapter.getDefaultAdapter().getRemoteLeDevice(TEST_DEVICE_ADDRESS,
                BluetoothDevice.ADDRESS_TYPE_RANDOM)

        const val QR_CODE_STRING =
            "BT:R:65536;T:1;D:00-A1-A1-A1-A1-A1;AS:1;B:123456;BN:VGVzdA==;" +
            "PM:BgNwVGVzdA==;SI:160;C:VGVzdENvZGU=;SG:BS:3,BM:3,AC:BQNUZXN0BARlbmc=;" +
            "VN:U;;"
    }
}