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

import android.app.Application
import android.os.Bundle
import androidx.annotation.IntDef
import com.android.settingslib.graph.proto.PreferenceValueProto
import com.android.settingslib.ipc.ApiDescriptor
import com.android.settingslib.ipc.ApiHandler
import com.android.settingslib.ipc.ApiPermissionChecker
import com.android.settingslib.ipc.IntMessageCodec
import com.android.settingslib.ipc.MessageCodec
import com.android.settingslib.metadata.BooleanValue
import com.android.settingslib.metadata.PersistentPreference
import com.android.settingslib.metadata.PreferenceAvailabilityProvider
import com.android.settingslib.metadata.PreferenceMetadata
import com.android.settingslib.metadata.PreferenceRestrictionProvider
import com.android.settingslib.metadata.PreferenceScreenRegistry
import com.android.settingslib.metadata.RangeValue
import com.android.settingslib.metadata.ReadWritePermit

/** Request to set preference value. */
data class PreferenceSetterRequest(
    val screenKey: String,
    val key: String,
    val value: PreferenceValueProto,
)

/** Result of preference setter request. */
@IntDef(
    PreferenceSetterResult.OK,
    PreferenceSetterResult.UNSUPPORTED,
    PreferenceSetterResult.DISABLED,
    PreferenceSetterResult.RESTRICTED,
    PreferenceSetterResult.UNAVAILABLE,
    PreferenceSetterResult.REQUIRE_APP_PERMISSION,
    PreferenceSetterResult.REQUIRE_USER_AGREEMENT,
    PreferenceSetterResult.DISALLOW,
    PreferenceSetterResult.INVALID_REQUEST,
    PreferenceSetterResult.INTERNAL_ERROR,
)
@Retention(AnnotationRetention.SOURCE)
annotation class PreferenceSetterResult {
    companion object {
        /** Set preference value successfully. */
        const val OK = 0
        /** Set preference value is unsupported on the preference. */
        const val UNSUPPORTED = 1
        /** Preference is disabled and cannot set preference value. */
        const val DISABLED = 2
        /** Preference is restricted by managed configuration and cannot set preference value. */
        const val RESTRICTED = 3
        /** Preference is unavailable and cannot set preference value. */
        const val UNAVAILABLE = 4
        /** Require (runtime/special) app permission from user explicitly. */
        const val REQUIRE_APP_PERMISSION = 5
        /** Require explicit user agreement (e.g. terms of service). */
        const val REQUIRE_USER_AGREEMENT = 6
        /** Disallow to set preference value (e.g. uid not allowed). */
        const val DISALLOW = 7
        /** Request is invalid. */
        const val INVALID_REQUEST = 8
        /** Internal error happened when persist preference value. */
        const val INTERNAL_ERROR = 9
    }
}

/** Preference setter API descriptor. */
class PreferenceSetterApiDescriptor(override val id: Int) :
    ApiDescriptor<PreferenceSetterRequest, Int> {

    override val requestCodec: MessageCodec<PreferenceSetterRequest>
        get() = PreferenceSetterRequestCodec

    override val responseCodec: MessageCodec<Int>
        get() = IntMessageCodec
}

/** Preference setter API implementation. */
class PreferenceSetterApiHandler(
    override val id: Int,
    private val permissionChecker: ApiPermissionChecker<PreferenceSetterRequest>,
) : ApiHandler<PreferenceSetterRequest, Int> {

    override fun hasPermission(
        application: Application,
        myUid: Int,
        callingUid: Int,
        request: PreferenceSetterRequest,
    ) = permissionChecker.hasPermission(application, myUid, callingUid, request)

    override suspend fun invoke(
        application: Application,
        myUid: Int,
        callingUid: Int,
        request: PreferenceSetterRequest,
    ): Int {
        val screenMetadata =
            PreferenceScreenRegistry[request.screenKey] ?: return PreferenceSetterResult.UNSUPPORTED
        val key = request.key
        val metadata =
            screenMetadata.getPreferenceHierarchy(application).find(key)
                ?: return PreferenceSetterResult.UNSUPPORTED
        if (metadata !is PersistentPreference<*>) return PreferenceSetterResult.UNSUPPORTED
        if (!metadata.isEnabled(application)) return PreferenceSetterResult.DISABLED
        if (metadata is PreferenceRestrictionProvider && metadata.isRestricted(application)) {
            return PreferenceSetterResult.RESTRICTED
        }
        if (metadata is PreferenceAvailabilityProvider && !metadata.isAvailable(application)) {
            return PreferenceSetterResult.UNAVAILABLE
        }

        fun <T> PreferenceMetadata.checkWritePermit(value: T): Int {
            @Suppress("UNCHECKED_CAST") val preference = (this as PersistentPreference<T>)
            return when (preference.getWritePermit(application, value, myUid, callingUid)) {
                ReadWritePermit.ALLOW -> PreferenceSetterResult.OK
                ReadWritePermit.DISALLOW -> PreferenceSetterResult.DISALLOW
                ReadWritePermit.REQUIRE_APP_PERMISSION ->
                    PreferenceSetterResult.REQUIRE_APP_PERMISSION
                ReadWritePermit.REQUIRE_USER_AGREEMENT ->
                    PreferenceSetterResult.REQUIRE_USER_AGREEMENT
                else -> PreferenceSetterResult.INTERNAL_ERROR
            }
        }

        val storage = metadata.storage(application)
        val value = request.value
        try {
            if (value.hasBooleanValue()) {
                if (metadata !is BooleanValue) return PreferenceSetterResult.INVALID_REQUEST
                val booleanValue = value.booleanValue
                val resultCode = metadata.checkWritePermit(booleanValue)
                if (resultCode != PreferenceSetterResult.OK) return resultCode
                storage.setBoolean(key, booleanValue)
                return PreferenceSetterResult.OK
            } else if (value.hasIntValue()) {
                val intValue = value.intValue
                val resultCode = metadata.checkWritePermit(intValue)
                if (resultCode != PreferenceSetterResult.OK) return resultCode
                if (metadata is RangeValue && !metadata.isValidValue(application, intValue)) {
                    return PreferenceSetterResult.INVALID_REQUEST
                }
                storage.setInt(key, intValue)
                return PreferenceSetterResult.OK
            }
        } catch (e: Exception) {
            return PreferenceSetterResult.INTERNAL_ERROR
        }
        return PreferenceSetterResult.INVALID_REQUEST
    }

    override val requestCodec: MessageCodec<PreferenceSetterRequest>
        get() = PreferenceSetterRequestCodec

    override val responseCodec: MessageCodec<Int>
        get() = IntMessageCodec
}

/** Message codec for [PreferenceSetterRequest]. */
object PreferenceSetterRequestCodec : MessageCodec<PreferenceSetterRequest> {
    override fun encode(data: PreferenceSetterRequest) =
        Bundle(3).apply {
            putString(SCREEN_KEY, data.screenKey)
            putString(KEY, data.key)
            putByteArray(null, data.value.toByteArray())
        }

    override fun decode(data: Bundle) =
        PreferenceSetterRequest(
            data.getString(SCREEN_KEY)!!,
            data.getString(KEY)!!,
            PreferenceValueProto.parseFrom(data.getByteArray(null)!!),
        )

    private const val SCREEN_KEY = "s"
    private const val KEY = "k"
}
