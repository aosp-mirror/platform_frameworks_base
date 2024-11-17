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

package com.android.settingslib.graph

import android.os.Bundle
import android.os.Parcel
import com.android.settingslib.graph.proto.PreferenceProto
import com.android.settingslib.ipc.MessageCodec
import java.util.Arrays

/** Message codec for [PreferenceGetterRequest]. */
class PreferenceGetterRequestCodec : MessageCodec<PreferenceGetterRequest> {
    override fun encode(data: PreferenceGetterRequest) =
        Bundle(2).apply {
            putParcelableArray(null, data.preferences)
            putInt(FLAGS, data.flags)
        }

    @Suppress("DEPRECATION")
    override fun decode(data: Bundle): PreferenceGetterRequest {
        data.classLoader = PreferenceCoordinate::class.java.classLoader
        val array = data.getParcelableArray(null)!!

        return PreferenceGetterRequest(
            Arrays.copyOf(array, array.size, Array<PreferenceCoordinate>::class.java),
            data.getInt(FLAGS),
        )
    }

    companion object {
        private const val FLAGS = "f"
    }
}

/** Message codec for [PreferenceGetterResponse]. */
class PreferenceGetterResponseCodec : MessageCodec<PreferenceGetterResponse> {
    override fun encode(data: PreferenceGetterResponse) =
        Bundle(2).apply {
            data.errors.toErrorsByteArray()?.let { putByteArray(ERRORS, it) }
            data.preferences.toPreferencesByteArray()?.let { putByteArray(null, it) }
        }

    private fun Map<PreferenceCoordinate, Int>.toErrorsByteArray(): ByteArray? {
        if (isEmpty()) return null
        val parcel = Parcel.obtain()
        parcel.writeInt(size)
        for ((coordinate, code) in this) {
            coordinate.writeToParcel(parcel, 0)
            parcel.writeInt(code)
        }
        val bytes = parcel.marshall()
        parcel.recycle()
        return bytes
    }

    private fun Map<PreferenceCoordinate, PreferenceProto>.toPreferencesByteArray(): ByteArray? {
        if (isEmpty()) return null
        val parcel = Parcel.obtain()
        parcel.writeInt(size)
        for ((coordinate, preferenceProto) in this) {
            coordinate.writeToParcel(parcel, 0)
            val data = preferenceProto.toByteArray()
            parcel.writeInt(data.size)
            parcel.writeByteArray(data)
        }
        val bytes = parcel.marshall()
        parcel.recycle()
        return bytes
    }

    override fun decode(data: Bundle) =
        PreferenceGetterResponse(
            data.getByteArray(ERRORS).toErrors(),
            data.getByteArray(null).toPreferences(),
        )

    private fun ByteArray?.toErrors(): Map<PreferenceCoordinate, Int> {
        if (this == null) return emptyMap()
        val parcel = Parcel.obtain()
        parcel.unmarshall(this, 0, size)
        parcel.setDataPosition(0)
        val count = parcel.readInt()
        val errors = mutableMapOf<PreferenceCoordinate, Int>()
        repeat(count) {
            val coordinate = PreferenceCoordinate(parcel)
            errors[coordinate] = parcel.readInt()
        }
        parcel.recycle()
        return errors
    }

    private fun ByteArray?.toPreferences(): Map<PreferenceCoordinate, PreferenceProto> {
        if (this == null) return emptyMap()
        val parcel = Parcel.obtain()
        parcel.unmarshall(this, 0, size)
        parcel.setDataPosition(0)
        val count = parcel.readInt()
        val preferences = mutableMapOf<PreferenceCoordinate, PreferenceProto>()
        repeat(count) {
            val coordinate = PreferenceCoordinate(parcel)
            val bytes = parcel.readInt()
            val array = ByteArray(bytes).also { parcel.readByteArray(it) }
            preferences[coordinate] = PreferenceProto.parseFrom(array)
        }
        parcel.recycle()
        return preferences
    }

    companion object {
        private const val ERRORS = "e"
    }
}
