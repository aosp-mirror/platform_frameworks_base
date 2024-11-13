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
import java.util.Locale

/** API to get preference graph. */
abstract class GetPreferenceGraphApiHandler(private val activityClasses: Set<String>) :
    ApiHandler<GetPreferenceGraphRequest, PreferenceGraphProto> {

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
        val builderRequest =
            if (request.activityClasses.isEmpty()) {
                GetPreferenceGraphRequest(activityClasses, request.visitedScreens, request.locale)
            } else {
                request
            }
        return PreferenceGraphBuilder.of(application, builderRequest).build()
    }
}

/**
 * Request of [GetPreferenceGraphApiHandler].
 *
 * @param activityClasses activities of the preference graph
 * @param visitedScreens keys of the visited preference screen
 * @param locale locale of the preference graph
 */
data class GetPreferenceGraphRequest
@JvmOverloads
constructor(
    val activityClasses: Set<String> = setOf(),
    val visitedScreens: Set<String> = setOf(),
    val locale: Locale? = null,
    val includeValue: Boolean = true,
)

object GetPreferenceGraphRequestCodec : MessageCodec<GetPreferenceGraphRequest> {
    override fun encode(data: GetPreferenceGraphRequest): Bundle =
        Bundle(3).apply {
            putStringArray(KEY_ACTIVITIES, data.activityClasses.toTypedArray())
            putStringArray(KEY_PREF_KEYS, data.visitedScreens.toTypedArray())
            putString(KEY_LOCALE, data.locale?.toLanguageTag())
        }

    override fun decode(data: Bundle): GetPreferenceGraphRequest {
        val activities = data.getStringArray(KEY_ACTIVITIES) ?: arrayOf()
        val visitedScreens = data.getStringArray(KEY_PREF_KEYS) ?: arrayOf()
        fun String?.toLocale() = if (this != null) Locale.forLanguageTag(this) else null
        return GetPreferenceGraphRequest(
            activities.toSet(),
            visitedScreens.toSet(),
            data.getString(KEY_LOCALE).toLocale(),
        )
    }

    private const val KEY_ACTIVITIES = "activities"
    private const val KEY_PREF_KEYS = "keys"
    private const val KEY_LOCALE = "locale"
}

object PreferenceGraphProtoCodec : MessageCodec<PreferenceGraphProto> {
    override fun encode(data: PreferenceGraphProto): Bundle =
        Bundle(1).apply { putByteArray(KEY_GRAPH, data.toByteArray()) }

    override fun decode(data: Bundle): PreferenceGraphProto =
        PreferenceGraphProto.parseFrom(data.getByteArray(KEY_GRAPH)!!)

    private const val KEY_GRAPH = "graph"
}
