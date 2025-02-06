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
import android.os.Parcelable
import android.os.SystemClock
import com.android.settingslib.graph.proto.PreferenceGraphProto
import com.android.settingslib.ipc.ApiHandler
import com.android.settingslib.ipc.ApiPermissionChecker
import com.android.settingslib.ipc.MessageCodec
import com.android.settingslib.metadata.PreferenceRemoteOpMetricsLogger
import com.android.settingslib.metadata.PreferenceScreenCoordinate
import com.android.settingslib.metadata.PreferenceScreenRegistry
import com.android.settingslib.preference.PreferenceScreenProvider
import java.util.Locale

/** API to get preference graph. */
class GetPreferenceGraphApiHandler(
    override val id: Int,
    private val permissionChecker: ApiPermissionChecker<GetPreferenceGraphRequest>,
    private val metricsLogger: PreferenceRemoteOpMetricsLogger? = null,
    private val preferenceScreenProviders: Set<Class<out PreferenceScreenProvider>> = emptySet(),
) : ApiHandler<GetPreferenceGraphRequest, PreferenceGraphProto> {

    override val requestCodec: MessageCodec<GetPreferenceGraphRequest>
        get() = GetPreferenceGraphRequestCodec

    override val responseCodec: MessageCodec<PreferenceGraphProto>
        get() = PreferenceGraphProtoCodec

    override fun hasPermission(
        application: Application,
        callingPid: Int,
        callingUid: Int,
        request: GetPreferenceGraphRequest,
    ) = permissionChecker.hasPermission(application, callingPid, callingUid, request)

    override suspend fun invoke(
        application: Application,
        callingPid: Int,
        callingUid: Int,
        request: GetPreferenceGraphRequest,
    ): PreferenceGraphProto {
        val elapsedRealtime = SystemClock.elapsedRealtime()
        var success = false
        try {
            val builder = PreferenceGraphBuilder.of(application, callingPid, callingUid, request)
            if (request.screens.isEmpty()) {
                val factories = PreferenceScreenRegistry.preferenceScreenMetadataFactories
                factories.forEachAsync { _, factory -> builder.addPreferenceScreen(factory) }
                for (provider in preferenceScreenProviders) {
                    builder.addPreferenceScreenProvider(provider)
                }
            }
            val result = builder.build()
            success = true
            return result
        } finally {
            metricsLogger?.logGraphApi(
                application,
                callingUid,
                success,
                SystemClock.elapsedRealtime() - elapsedRealtime,
            )
        }
    }
}

/**
 * Request of [GetPreferenceGraphApiHandler].
 *
 * @param screens screens of the preference graph
 * @param visitedScreens visited preference screens
 * @param locale locale of the preference graph
 */
data class GetPreferenceGraphRequest
@JvmOverloads
constructor(
    val screens: Set<PreferenceScreenCoordinate> = setOf(),
    val visitedScreens: Set<PreferenceScreenCoordinate> = setOf(),
    val locale: Locale? = null,
    val flags: Int = PreferenceGetterFlags.ALL,
    val includeValueDescriptor: Boolean = true,
)

object GetPreferenceGraphRequestCodec : MessageCodec<GetPreferenceGraphRequest> {
    override fun encode(data: GetPreferenceGraphRequest): Bundle =
        Bundle(4).apply {
            putParcelableArray(KEY_SCREENS, data.screens.toTypedArray())
            putParcelableArray(KEY_VISITED_SCREENS, data.visitedScreens.toTypedArray())
            putString(KEY_LOCALE, data.locale?.toLanguageTag())
            putInt(KEY_FLAGS, data.flags)
        }

    @Suppress("DEPRECATION")
    override fun decode(data: Bundle): GetPreferenceGraphRequest {
        data.classLoader = PreferenceScreenCoordinate::class.java.classLoader
        val screens = data.getParcelableArray(KEY_SCREENS) ?: arrayOf()
        val visitedScreens = data.getParcelableArray(KEY_VISITED_SCREENS) ?: arrayOf()
        fun String?.toLocale() = if (this != null) Locale.forLanguageTag(this) else null
        fun Array<Parcelable>.toScreenCoordinates() =
            buildSet(size) {
                for (element in this@toScreenCoordinates) add(element as PreferenceScreenCoordinate)
            }
        return GetPreferenceGraphRequest(
            screens.toScreenCoordinates(),
            visitedScreens.toScreenCoordinates(),
            data.getString(KEY_LOCALE).toLocale(),
            data.getInt(KEY_FLAGS),
        )
    }

    private const val KEY_SCREENS = "s"
    private const val KEY_VISITED_SCREENS = "v"
    private const val KEY_LOCALE = "l"
    private const val KEY_FLAGS = "f"
}

object PreferenceGraphProtoCodec : MessageCodec<PreferenceGraphProto> {
    override fun encode(data: PreferenceGraphProto): Bundle =
        Bundle(1).apply { putByteArray(KEY_GRAPH, data.toByteArray()) }

    override fun decode(data: Bundle): PreferenceGraphProto =
        PreferenceGraphProto.parseFrom(data.getByteArray(KEY_GRAPH)!!)

    private const val KEY_GRAPH = "g"
}
