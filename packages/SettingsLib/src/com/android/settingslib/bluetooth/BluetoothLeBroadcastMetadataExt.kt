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

import android.annotation.TargetApi
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothLeAudioCodecConfigMetadata
import android.bluetooth.BluetoothLeAudioContentMetadata
import android.bluetooth.BluetoothLeBroadcastChannel
import android.bluetooth.BluetoothLeBroadcastMetadata
import android.bluetooth.BluetoothLeBroadcastSubgroup
import android.os.Build
import android.util.Base64
import android.util.Log
import com.android.settingslib.bluetooth.BluetoothBroadcastUtils.SCHEME_BT_BROADCAST_METADATA

object BluetoothLeBroadcastMetadataExt {
    private const val TAG = "BtLeBroadcastMetadataExt"

    // BluetoothLeBroadcastMetadata
    private const val KEY_BT_QR_VER = "R"
    private const val KEY_BT_ADDRESS_TYPE = "T"
    private const val KEY_BT_DEVICE = "D"
    private const val KEY_BT_ADVERTISING_SID = "AS"
    private const val KEY_BT_BROADCAST_ID = "B"
    private const val KEY_BT_BROADCAST_NAME = "BN"
    private const val KEY_BT_PUBLIC_BROADCAST_DATA = "PM"
    private const val KEY_BT_SYNC_INTERVAL = "SI"
    private const val KEY_BT_BROADCAST_CODE = "C"
    private const val KEY_BT_SUBGROUPS = "SG"
    private const val KEY_BT_VENDOR_SPECIFIC = "V"
    private const val KEY_BT_ANDROID_VERSION = "VN"

    // Subgroup data
    private const val KEY_BTSG_BIS_SYNC = "BS"
    private const val KEY_BTSG_BIS_MASK = "BM"
    private const val KEY_BTSG_AUDIO_CONTENT = "AC"

    // Vendor specific data
    private const val KEY_BTVSD_COMPANY_ID = "VI"
    private const val KEY_BTVSD_VENDOR_DATA = "VD"

    private const val DELIMITER_KEY_VALUE = ":"
    private const val DELIMITER_BT_LEVEL_1 = ";"
    private const val DELIMITER_BT_LEVEL_2 = ","

    private const val SUFFIX_QR_CODE = ";;"

    private const val ANDROID_VER = "U"
    private const val QR_CODE_VER = 0x010000

    // BT constants
    private const val BIS_SYNC_MAX_CHANNEL = 32
    private const val BIS_SYNC_NO_PREFERENCE = 0xFFFFFFFFu
    private const val SUBGROUP_LC3_CODEC_ID = 0x6L

    /**
     * Converts [BluetoothLeBroadcastMetadata] to QR code string.
     *
     * QR code string will prefix with "BT:".
     */
    fun BluetoothLeBroadcastMetadata.toQrCodeString(): String {
        val entries = mutableListOf<Pair<String, String>>()
        entries.add(Pair(KEY_BT_QR_VER, QR_CODE_VER.toString()))
        entries.add(Pair(KEY_BT_ADDRESS_TYPE, this.sourceAddressType.toString()))
        entries.add(Pair(KEY_BT_DEVICE, this.sourceDevice.address.replace(":", "-")))
        entries.add(Pair(KEY_BT_ADVERTISING_SID, this.sourceAdvertisingSid.toString()))
        entries.add(Pair(KEY_BT_BROADCAST_ID, this.broadcastId.toString()))
        if (this.broadcastName != null) {
            entries.add(Pair(KEY_BT_BROADCAST_NAME, Base64.encodeToString(
                this.broadcastName?.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)))
        }
        if (this.publicBroadcastMetadata != null) {
            entries.add(Pair(KEY_BT_PUBLIC_BROADCAST_DATA, Base64.encodeToString(
                this.publicBroadcastMetadata?.rawMetadata, Base64.NO_WRAP)))
        }
        entries.add(Pair(KEY_BT_SYNC_INTERVAL, this.paSyncInterval.toString()))
        if (this.broadcastCode != null) {
            entries.add(Pair(KEY_BT_BROADCAST_CODE,
                Base64.encodeToString(this.broadcastCode, Base64.NO_WRAP)))
        }
        this.subgroups.forEach {
                subgroup -> entries.add(Pair(KEY_BT_SUBGROUPS, subgroup.toQrCodeString())) }
        entries.add(Pair(KEY_BT_ANDROID_VERSION, ANDROID_VER))
        val qrCodeString = SCHEME_BT_BROADCAST_METADATA +
                entries.toQrCodeString(DELIMITER_BT_LEVEL_1) + SUFFIX_QR_CODE
        Log.d(TAG, "Generated QR string : $qrCodeString")
        return qrCodeString
    }

    /**
     * Converts QR code string to [BluetoothLeBroadcastMetadata].
     *
     * QR code string should prefix with "BT:BluetoothLeBroadcastMetadata:".
     */
    fun convertToBroadcastMetadata(qrCodeString: String): BluetoothLeBroadcastMetadata? {
        if (!qrCodeString.startsWith(SCHEME_BT_BROADCAST_METADATA)) {
            Log.e(TAG, "String \"$qrCodeString\" does not begin with " +
                    "\"$SCHEME_BT_BROADCAST_METADATA\"")
            return null
        }
        return try {
            Log.d(TAG, "Parsing QR string: $qrCodeString")
            val strippedString =
                    qrCodeString.removePrefix(SCHEME_BT_BROADCAST_METADATA)
                            .removeSuffix(SUFFIX_QR_CODE)
            Log.d(TAG, "Stripped to: $strippedString")
            parseQrCodeToMetadata(strippedString)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot parse: $qrCodeString", e)
            null
        }
    }

    private fun BluetoothLeBroadcastSubgroup.toQrCodeString(): String {
        val entries = mutableListOf<Pair<String, String>>()
        entries.add(Pair(KEY_BTSG_BIS_SYNC, getBisSyncFromChannels(this.channels).toString()))
        entries.add(Pair(KEY_BTSG_BIS_MASK, getBisMaskFromChannels(this.channels).toString()))
        entries.add(Pair(KEY_BTSG_AUDIO_CONTENT,
            Base64.encodeToString(this.contentMetadata.rawMetadata, Base64.NO_WRAP)))
        return entries.toQrCodeString(DELIMITER_BT_LEVEL_2)
    }

    private fun List<Pair<String, String>>.toQrCodeString(delimiter: String): String {
        val entryStrings = this.map{ it.first + DELIMITER_KEY_VALUE + it.second }
        return entryStrings.joinToString(separator = delimiter)
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private fun parseQrCodeToMetadata(input: String): BluetoothLeBroadcastMetadata {
        // Split into a list of list
        val level1Fields = input.split(DELIMITER_BT_LEVEL_1)
            .map{it.split(DELIMITER_KEY_VALUE, limit = 2)}
        var qrCodeVersion = -1
        var sourceAddrType = BluetoothDevice.ADDRESS_TYPE_UNKNOWN
        var sourceAddrString: String? = null
        var sourceAdvertiserSid = -1
        var broadcastId = -1
        var broadcastName: String? = null
        var publicBroadcastMetadata: BluetoothLeAudioContentMetadata? = null
        var paSyncInterval = -1
        var broadcastCode: ByteArray? = null
        // List of VendorID -> Data Pairs
        var vendorDataList = mutableListOf<Pair<Int, ByteArray?>>()
        var androidVersion: String? = null
        val builder = BluetoothLeBroadcastMetadata.Builder()

        for (field: List<String> in level1Fields) {
            if (field.isEmpty()) {
                continue
            }
            val key = field[0]
            // Ignore 3rd value and after
            val value = if (field.size > 1) field[1] else ""
            when (key) {
                KEY_BT_QR_VER -> {
                    require(qrCodeVersion == -1) { "Duplicate qrCodeVersion: $input" }
                    qrCodeVersion = value.toInt()
                }
                KEY_BT_ADDRESS_TYPE -> {
                    require(sourceAddrType == BluetoothDevice.ADDRESS_TYPE_UNKNOWN) {
                        "Duplicate sourceAddrType: $input"
                    }
                    sourceAddrType = value.toInt()
                }
                KEY_BT_DEVICE -> {
                    require(sourceAddrString == null) { "Duplicate sourceAddr: $input" }
                    sourceAddrString = value.replace("-", ":")
                }
                KEY_BT_ADVERTISING_SID -> {
                    require(sourceAdvertiserSid == -1) { "Duplicate sourceAdvertiserSid: $input" }
                    sourceAdvertiserSid = value.toInt()
                }
                KEY_BT_BROADCAST_ID -> {
                    require(broadcastId == -1) { "Duplicate broadcastId: $input" }
                    broadcastId = value.toInt()
                }
                KEY_BT_BROADCAST_NAME -> {
                    require(broadcastName == null) { "Duplicate broadcastName: $input" }
                    broadcastName = String(Base64.decode(value, Base64.NO_WRAP))
                }
                KEY_BT_PUBLIC_BROADCAST_DATA -> {
                    require(publicBroadcastMetadata == null) {
                        "Duplicate publicBroadcastMetadata $input"
                    }
                    publicBroadcastMetadata = BluetoothLeAudioContentMetadata
                        .fromRawBytes(Base64.decode(value, Base64.NO_WRAP))
                }
                KEY_BT_SYNC_INTERVAL -> {
                    require(paSyncInterval == -1) { "Duplicate paSyncInterval: $input" }
                    paSyncInterval = value.toInt()
                }
                KEY_BT_BROADCAST_CODE -> {
                    require(broadcastCode == null) { "Duplicate broadcastCode: $input" }
                    broadcastCode = Base64.decode(value, Base64.NO_WRAP)
                }
                KEY_BT_ANDROID_VERSION -> {
                    require(androidVersion == null) { "Duplicate androidVersion: $input" }
                    androidVersion = value
                    Log.i(TAG, "QR code Android version: $androidVersion")
                }
                // Repeatable
                KEY_BT_SUBGROUPS -> {
                    builder.addSubgroup(parseSubgroupData(value))
                }
                // Repeatable
                KEY_BT_VENDOR_SPECIFIC -> {
                    vendorDataList.add(parseVendorData(value))
                }
            }
        }
        Log.d(TAG, "parseQrCodeToMetadata: sourceAddrType=$sourceAddrType, " +
                "sourceAddr=$sourceAddrString, sourceAdvertiserSid=$sourceAdvertiserSid, " +
                "broadcastId=$broadcastId, broadcastName=$broadcastName, " +
                "publicBroadcastMetadata=${publicBroadcastMetadata != null}, " +
                "paSyncInterval=$paSyncInterval, " +
                "broadcastCode=${broadcastCode?.toString(Charsets.UTF_8)}")
        Log.d(TAG, "Not used in current code, but part of the specification: " +
                "qrCodeVersion=$qrCodeVersion, androidVersion=$androidVersion, " +
                "vendorDataListSize=${vendorDataList.size}")
        val adapter = BluetoothAdapter.getDefaultAdapter()
        // add source device and set broadcast code
        val device = adapter.getRemoteLeDevice(requireNotNull(sourceAddrString), sourceAddrType)
        builder.apply {
            setSourceDevice(device, sourceAddrType)
            setSourceAdvertisingSid(sourceAdvertiserSid)
            setBroadcastId(broadcastId)
            setBroadcastName(broadcastName)
            setPublicBroadcast(publicBroadcastMetadata != null)
            setPublicBroadcastMetadata(publicBroadcastMetadata)
            setPaSyncInterval(paSyncInterval)
            setEncrypted(broadcastCode != null)
            setBroadcastCode(broadcastCode)
            // Presentation delay is unknown and not useful when adding source
            // Broadcast sink needs to sync to the Broadcast source to get presentation delay
            setPresentationDelayMicros(0)
        }
        return builder.build()
    }

    private fun parseSubgroupData(input: String): BluetoothLeBroadcastSubgroup {
        Log.d(TAG, "parseSubgroupData: $input")
        val fields = input.split(DELIMITER_BT_LEVEL_2)
        var bisSync: UInt? = null
        var bisMask: UInt? = null
        var metadata: ByteArray? = null

        fields.forEach { field ->
            val(key, value) = field.split(DELIMITER_KEY_VALUE)
            when (key) {
                KEY_BTSG_BIS_SYNC -> {
                    require(bisSync == null) { "Duplicate bisSync: $input" }
                    bisSync = value.toUInt()
                }
                KEY_BTSG_BIS_MASK -> {
                    require(bisMask == null) { "Duplicate bisMask: $input" }
                    bisMask = value.toUInt()
                }
                KEY_BTSG_AUDIO_CONTENT -> {
                    require(metadata == null) { "Duplicate metadata: $input" }
                    metadata = Base64.decode(value, Base64.NO_WRAP)
                }
            }
        }
        val channels = convertToChannels(requireNotNull(bisSync), requireNotNull(bisMask))
        val audioCodecConfigMetadata = BluetoothLeAudioCodecConfigMetadata.Builder()
                .setAudioLocation(0).build()
        return BluetoothLeBroadcastSubgroup.Builder().apply {
            setCodecId(SUBGROUP_LC3_CODEC_ID)
            setCodecSpecificConfig(audioCodecConfigMetadata)
            setContentMetadata(
                    BluetoothLeAudioContentMetadata.fromRawBytes(metadata ?: ByteArray(0)))
            channels.forEach(::addChannel)
        }.build()
    }

    private fun parseVendorData(input: String): Pair<Int, ByteArray?> {
        var companyId = -1
        var data: ByteArray? = null
        val fields = input.split(DELIMITER_BT_LEVEL_2)
        fields.forEach { field ->
            val(key, value) = field.split(DELIMITER_KEY_VALUE)
            when (key) {
                KEY_BTVSD_COMPANY_ID -> {
                    require(companyId == -1) { "Duplicate companyId: $input" }
                    companyId = value.toInt()
                }
                KEY_BTVSD_VENDOR_DATA -> {
                    require(data == null) { "Duplicate data: $input" }
                    data = Base64.decode(value, Base64.NO_WRAP)
                }
            }
        }
        return Pair(companyId, data)
    }

    private fun getBisSyncFromChannels(channels: List<BluetoothLeBroadcastChannel>): UInt {
        var bisSync = 0u
        // channel index starts from 1
        channels.forEach { channel ->
            if (channel.isSelected && channel.channelIndex > 0) {
                bisSync = bisSync or (1u shl (channel.channelIndex - 1))
            }
        }
        // No channel is selected means no preference on Android platform
        return if (bisSync == 0u) BIS_SYNC_NO_PREFERENCE else bisSync
    }

    private fun getBisMaskFromChannels(channels: List<BluetoothLeBroadcastChannel>): UInt {
        var bisMask = 0u
        // channel index starts from 1
        channels.forEach { channel ->
            if (channel.channelIndex > 0) {
                bisMask = bisMask or (1u shl (channel.channelIndex - 1))
            }
        }
        return bisMask
    }

    private fun convertToChannels(bisSync: UInt, bisMask: UInt):
            List<BluetoothLeBroadcastChannel> {
        Log.d(TAG, "convertToChannels: bisSync=$bisSync, bisMask=$bisMask")
        var selectionMask = bisSync
        if (bisSync != BIS_SYNC_NO_PREFERENCE) {
            require(bisMask == (bisMask or bisSync)) {
                "bisSync($bisSync) must select a subset of bisMask($bisMask) if it has preferences"
            }
        } else {
            // No channel preference means no channel is selected
            selectionMask = 0u
        }
        val channels = mutableListOf<BluetoothLeBroadcastChannel>()
        val audioCodecConfigMetadata = BluetoothLeAudioCodecConfigMetadata.Builder()
                .setAudioLocation(0).build()
        for (i in 0 until BIS_SYNC_MAX_CHANNEL) {
            val channelMask = 1u shl i
            if ((bisMask and channelMask) != 0u) {
                val channel = BluetoothLeBroadcastChannel.Builder().apply {
                    setSelected((selectionMask and channelMask) != 0u)
                    setChannelIndex(i + 1)
                    setCodecMetadata(audioCodecConfigMetadata)
                }
                channels.add(channel.build())
            }
        }
        return channels
    }
}