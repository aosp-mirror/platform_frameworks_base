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

import android.bluetooth.BluetoothLeBroadcastMetadata
import android.os.Parcel
import android.os.Parcelable
import android.util.Base64
import android.util.Log
import com.android.settingslib.bluetooth.BluetoothBroadcastUtils.SCHEME_BT_BROADCAST_METADATA

object BluetoothLeBroadcastMetadataExt {
    private const val TAG = "BluetoothLeBroadcastMetadataExt"

    /**
     * Converts [BluetoothLeBroadcastMetadata] to QR code string.
     *
     * QR code string will prefix with "BT:BluetoothLeBroadcastMetadata:".
     */
    fun BluetoothLeBroadcastMetadata.toQrCodeString(): String =
        SCHEME_BT_BROADCAST_METADATA + Base64.encodeToString(toBytes(this), Base64.NO_WRAP)

    /**
     * Converts QR code string to [BluetoothLeBroadcastMetadata].
     *
     * QR code string should prefix with "BT:BluetoothLeBroadcastMetadata:".
     */
    fun convertToBroadcastMetadata(qrCodeString: String): BluetoothLeBroadcastMetadata? {
        if (!qrCodeString.startsWith(SCHEME_BT_BROADCAST_METADATA)) return null
        return try {
            val encodedString = qrCodeString.removePrefix(SCHEME_BT_BROADCAST_METADATA)
            val bytes = Base64.decode(encodedString, Base64.NO_WRAP)
            createFromBytes(BluetoothLeBroadcastMetadata.CREATOR, bytes)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot convert QR code string to BluetoothLeBroadcastMetadata", e)
            null
        }
    }

    private fun toBytes(parcelable: Parcelable): ByteArray =
        Parcel.obtain().run {
            parcelable.writeToParcel(this, 0)
            setDataPosition(0)
            val bytes = marshall()
            recycle()
            bytes
        }

    private fun <T> createFromBytes(creator: Parcelable.Creator<T>, bytes: ByteArray): T =
        Parcel.obtain().run {
            unmarshall(bytes, 0, bytes.size)
            setDataPosition(0)
            val created = creator.createFromParcel(this)
            recycle()
            created
        }
}