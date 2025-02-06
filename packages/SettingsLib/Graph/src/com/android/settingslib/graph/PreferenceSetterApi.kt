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
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import androidx.annotation.IntDef
import com.android.settingslib.graph.proto.PreferenceValueProto
import com.android.settingslib.ipc.ApiDescriptor
import com.android.settingslib.ipc.ApiHandler
import com.android.settingslib.ipc.ApiPermissionChecker
import com.android.settingslib.ipc.IntMessageCodec
import com.android.settingslib.ipc.MessageCodec
import com.android.settingslib.metadata.IntRangeValuePreference
import com.android.settingslib.metadata.PersistentPreference
import com.android.settingslib.metadata.PreferenceAvailabilityProvider
import com.android.settingslib.metadata.PreferenceCoordinate
import com.android.settingslib.metadata.PreferenceMetadata
import com.android.settingslib.metadata.PreferenceRemoteOpMetricsLogger
import com.android.settingslib.metadata.PreferenceRestrictionProvider
import com.android.settingslib.metadata.PreferenceScreenRegistry
import com.android.settingslib.metadata.ReadWritePermit
import com.android.settingslib.metadata.SensitivityLevel.Companion.HIGH_SENSITIVITY
import com.android.settingslib.metadata.SensitivityLevel.Companion.UNKNOWN_SENSITIVITY

/** Request to set preference value. */
class PreferenceSetterRequest(
    screenKey: String,
    args: Bundle?,
    key: String,
    val value: PreferenceValueProto,
) : PreferenceCoordinate(screenKey, args, key)

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
    private val metricsLogger: PreferenceRemoteOpMetricsLogger? = null,
) : ApiHandler<PreferenceSetterRequest, Int> {

    override fun hasPermission(
        application: Application,
        callingPid: Int,
        callingUid: Int,
        request: PreferenceSetterRequest,
    ) = permissionChecker.hasPermission(application, callingPid, callingUid, request)

    override suspend fun invoke(
        application: Application,
        callingPid: Int,
        callingUid: Int,
        request: PreferenceSetterRequest,
    ): Int {
        val elapsedRealtime = SystemClock.elapsedRealtime()
        fun notFound(): Int {
            metricsLogger?.logSetterApi(
                application,
                callingUid,
                request,
                null,
                null,
                PreferenceSetterResult.UNSUPPORTED,
                SystemClock.elapsedRealtime() - elapsedRealtime,
            )
            return PreferenceSetterResult.UNSUPPORTED
        }
        val screenMetadata =
            PreferenceScreenRegistry.create(application, request) ?: return notFound()
        val key = request.key
        val metadata =
            screenMetadata.getPreferenceHierarchy(application).find(key) ?: return notFound()

        fun <T> PreferenceMetadata.checkWritePermit(value: T): Int {
            @Suppress("UNCHECKED_CAST") val preference = (this as PersistentPreference<T>)
            return when (preference.evalWritePermit(application, value, callingPid, callingUid)) {
                ReadWritePermit.ALLOW -> PreferenceSetterResult.OK
                ReadWritePermit.DISALLOW -> PreferenceSetterResult.DISALLOW
                ReadWritePermit.REQUIRE_APP_PERMISSION ->
                    PreferenceSetterResult.REQUIRE_APP_PERMISSION
                ReadWritePermit.REQUIRE_USER_AGREEMENT ->
                    PreferenceSetterResult.REQUIRE_USER_AGREEMENT
                else -> PreferenceSetterResult.INTERNAL_ERROR
            }
        }

        fun invoke(): Int {
            if (metadata !is PersistentPreference<*>) return PreferenceSetterResult.UNSUPPORTED
            if (!metadata.isEnabled(application)) return PreferenceSetterResult.DISABLED
            if (metadata is PreferenceRestrictionProvider && metadata.isRestricted(application)) {
                return PreferenceSetterResult.RESTRICTED
            }
            if (metadata is PreferenceAvailabilityProvider && !metadata.isAvailable(application)) {
                return PreferenceSetterResult.UNAVAILABLE
            }

            val storage = metadata.storage(application)
            val value = request.value
            try {
                if (value.hasBooleanValue()) {
                    if (metadata.valueType != Boolean::class.javaObjectType) {
                        return PreferenceSetterResult.INVALID_REQUEST
                    }
                    val booleanValue = value.booleanValue
                    val resultCode = metadata.checkWritePermit(booleanValue)
                    if (resultCode != PreferenceSetterResult.OK) return resultCode
                    storage.setBoolean(key, booleanValue)
                    return PreferenceSetterResult.OK
                } else if (value.hasIntValue()) {
                    val intValue = value.intValue
                    val resultCode = metadata.checkWritePermit(intValue)
                    if (resultCode != PreferenceSetterResult.OK) return resultCode
                    if (
                        metadata is IntRangeValuePreference &&
                            !metadata.isValidValue(application, intValue)
                    ) {
                        return PreferenceSetterResult.INVALID_REQUEST
                    }
                    storage.setInt(key, intValue)
                    return PreferenceSetterResult.OK
                } else if (value.hasFloatValue()) {
                    val floatValue = value.floatValue
                    val resultCode = metadata.checkWritePermit(floatValue)
                    if (resultCode != PreferenceSetterResult.OK) return resultCode
                    storage.setFloat(key, floatValue)
                    return PreferenceSetterResult.OK
                }
            } catch (e: Exception) {
                return PreferenceSetterResult.INTERNAL_ERROR
            }
            return PreferenceSetterResult.INVALID_REQUEST
        }

        val result = invoke()
        metricsLogger?.logSetterApi(
            application,
            callingUid,
            request,
            screenMetadata,
            metadata,
            result,
            SystemClock.elapsedRealtime() - elapsedRealtime,
        )
        return result
    }

    override val requestCodec: MessageCodec<PreferenceSetterRequest>
        get() = PreferenceSetterRequestCodec

    override val responseCodec: MessageCodec<Int>
        get() = IntMessageCodec
}

/** Evaluates the write permit of a persistent preference. */
fun <T> PersistentPreference<T>.evalWritePermit(
    context: Context,
    value: T?,
    callingPid: Int,
    callingUid: Int,
): Int =
    when {
        sensitivityLevel == UNKNOWN_SENSITIVITY || sensitivityLevel == HIGH_SENSITIVITY ->
            ReadWritePermit.DISALLOW
        getWritePermissions(context)?.check(context, callingPid, callingUid) == false ->
            ReadWritePermit.REQUIRE_APP_PERMISSION
        else -> getWritePermit(context, value, callingPid, callingUid)
    }

/** Message codec for [PreferenceSetterRequest]. */
object PreferenceSetterRequestCodec : MessageCodec<PreferenceSetterRequest> {
    override fun encode(data: PreferenceSetterRequest) =
        Bundle(3).apply {
            putString(SCREEN_KEY, data.screenKey)
            putBundle(ARGS, data.args)
            putString(KEY, data.key)
            putByteArray(null, data.value.toByteArray())
        }

    override fun decode(data: Bundle) =
        PreferenceSetterRequest(
            data.getString(SCREEN_KEY)!!,
            data.getBundle(ARGS),
            data.getString(KEY)!!,
            PreferenceValueProto.parseFrom(data.getByteArray(null)!!),
        )

    private const val SCREEN_KEY = "s"
    private const val KEY = "k"
    private const val ARGS = "a"
}
