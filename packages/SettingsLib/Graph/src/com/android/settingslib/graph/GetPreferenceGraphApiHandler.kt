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
import com.android.settingslib.graph.proto.PreferenceGraphProto
import com.android.settingslib.ipc.ApiHandler
import com.android.settingslib.ipc.MessageCodec
import com.android.settingslib.metadata.PreferenceScreenRegistry
import com.android.settingslib.preference.PreferenceScreenProvider
import java.util.Locale

/** API to get preference graph. */
abstract class GetPreferenceGraphApiHandler(
    private val preferenceScreenProviders: Set<Class<out PreferenceScreenProvider>>
) : ApiHandler<GetPreferenceGraphRequest, PreferenceGraphProto> {

    override val requestCodec: MessageCodec<GetPreferenceGraphRequest>
        get() = GetPreferenceGraphRequestCodec

    override val responseCodec: MessageCodec<PreferenceGraphProto>
        get() = PreferenceGraphProtoCodec

    override suspend fun invoke(
        application: Application,
        myUid: Int,
        callingUid: Int,
        request: GetPreferenceGraphRequest,
    ): PreferenceGraphProto {
        val builder = PreferenceGraphBuilder.of(application, myUid, callingUid, request)
        if (request.screenKeys.isEmpty()) {
            for (key in PreferenceScreenRegistry.preferenceScreens.keys) {
                builder.addPreferenceScreenFromRegistry(key)
            }
            for (provider in preferenceScreenProviders) {
                builder.addPreferenceScreenProvider(provider)
            }
        }
        return builder.build()
    }
}

/**
 * Request of [GetPreferenceGraphApiHandler].
 *
 * @param screenKeys screen keys of the preference graph
 * @param visitedScreens keys of the visited preference screen
 * @param locale locale of the preference graph
 */
data class GetPreferenceGraphRequest
@JvmOverloads
constructor(
    val screenKeys: Set<String> = setOf(),
    val visitedScreens: Set<String> = setOf(),
    val locale: Locale? = null,
    val flags: Int = PreferenceGetterFlags.ALL,
    val includeValue: Boolean = true, // TODO: clean up
    val includeValueDescriptor: Boolean = true,
)

object GetPreferenceGraphRequestCodec : MessageCodec<GetPreferenceGraphRequest> {
    override fun encode(data: GetPreferenceGraphRequest): Bundle =
        Bundle(4).apply {
            putStringArray(KEY_SCREEN_KEYS, data.screenKeys.toTypedArray())
            putStringArray(KEY_VISITED_KEYS, data.visitedScreens.toTypedArray())
            putString(KEY_LOCALE, data.locale?.toLanguageTag())
            putInt(KEY_FLAGS, data.flags)
        }

    override fun decode(data: Bundle): GetPreferenceGraphRequest {
        val screenKeys = data.getStringArray(KEY_SCREEN_KEYS) ?: arrayOf()
        val visitedScreens = data.getStringArray(KEY_VISITED_KEYS) ?: arrayOf()
        fun String?.toLocale() = if (this != null) Locale.forLanguageTag(this) else null
        return GetPreferenceGraphRequest(
            screenKeys.toSet(),
            visitedScreens.toSet(),
            data.getString(KEY_LOCALE).toLocale(),
            data.getInt(KEY_FLAGS),
        )
    }

    private const val KEY_SCREEN_KEYS = "k"
    private const val KEY_VISITED_KEYS = "v"
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
