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
import androidx.annotation.IntDef
import com.android.settingslib.graph.proto.PreferenceProto
import com.android.settingslib.ipc.ApiDescriptor
import com.android.settingslib.ipc.ApiHandler
import com.android.settingslib.ipc.ApiPermissionChecker
import com.android.settingslib.metadata.PreferenceHierarchyNode
import com.android.settingslib.metadata.PreferenceScreenRegistry

/**
 * Request to get preference information.
 *
 * @param preferences coordinate of preferences
 * @param flags a combination of constants in [PreferenceGetterFlags]
 */
class PreferenceGetterRequest(val preferences: Array<PreferenceCoordinate>, val flags: Int)

/** Error code of preference getter request. */
@Target(AnnotationTarget.TYPE)
@IntDef(
    PreferenceGetterErrorCode.NOT_FOUND,
    PreferenceGetterErrorCode.DISALLOW,
    PreferenceGetterErrorCode.INTERNAL_ERROR,
)
@Retention(AnnotationRetention.SOURCE)
annotation class PreferenceGetterErrorCode {
    companion object {
        /** Preference is not found. */
        const val NOT_FOUND = 1
        /** Disallow to get preference value (e.g. uid not allowed). */
        const val DISALLOW = 2
        /** Internal error happened when get preference information. */
        const val INTERNAL_ERROR = 3

        fun getMessage(code: Int) =
            when (code) {
                NOT_FOUND -> "Preference not found"
                DISALLOW -> "Disallow to get preference value"
                INTERNAL_ERROR -> "Internal error"
                else -> "Unknown error"
            }
    }
}

/** Response of the getter API. */
class PreferenceGetterResponse(
    val errors: Map<PreferenceCoordinate, @PreferenceGetterErrorCode Int>,
    val preferences: Map<PreferenceCoordinate, PreferenceProto>,
)

/** Preference getter API descriptor. */
class PreferenceGetterApiDescriptor(override val id: Int) :
    ApiDescriptor<PreferenceGetterRequest, PreferenceGetterResponse> {

    override val requestCodec = PreferenceGetterRequestCodec()

    override val responseCodec = PreferenceGetterResponseCodec()
}

/** Preference getter API implementation. */
class PreferenceGetterApiHandler(
    override val id: Int,
    private val permissionChecker: ApiPermissionChecker<PreferenceGetterRequest>,
) : ApiHandler<PreferenceGetterRequest, PreferenceGetterResponse> {

    override fun hasPermission(
        application: Application,
        myUid: Int,
        callingUid: Int,
        request: PreferenceGetterRequest,
    ) = permissionChecker.hasPermission(application, myUid, callingUid, request)

    override suspend fun invoke(
        application: Application,
        myUid: Int,
        callingUid: Int,
        request: PreferenceGetterRequest,
    ): PreferenceGetterResponse {
        val errors = mutableMapOf<PreferenceCoordinate, Int>()
        val preferences = mutableMapOf<PreferenceCoordinate, PreferenceProto>()
        val flags = request.flags
        for ((screenKey, coordinates) in request.preferences.groupBy { it.screenKey }) {
            val screenMetadata = PreferenceScreenRegistry[screenKey]
            if (screenMetadata == null) {
                for (coordinate in coordinates) {
                    errors[coordinate] = PreferenceGetterErrorCode.NOT_FOUND
                }
                continue
            }
            val nodes = mutableMapOf<String, PreferenceHierarchyNode?>()
            for (coordinate in coordinates) nodes[coordinate.key] = null
            screenMetadata.getPreferenceHierarchy(application).forEachRecursively {
                val metadata = it.metadata
                val key = metadata.key
                if (nodes.containsKey(key)) nodes[key] = it
            }
            for (coordinate in coordinates) {
                val node = nodes[coordinate.key]
                if (node == null) {
                    errors[coordinate] = PreferenceGetterErrorCode.NOT_FOUND
                    continue
                }
                val metadata = node.metadata
                try {
                    val preferenceProto =
                        metadata.toProto(
                            application,
                            myUid,
                            callingUid,
                            screenMetadata,
                            metadata.key == screenMetadata.key,
                            flags,
                        )
                    if (flags == PreferenceGetterFlags.VALUE && !preferenceProto.hasValue()) {
                        errors[coordinate] = PreferenceGetterErrorCode.DISALLOW
                    } else {
                        preferences[coordinate] = preferenceProto
                    }
                } catch (e: Exception) {
                    errors[coordinate] = PreferenceGetterErrorCode.INTERNAL_ERROR
                }
            }
        }
        return PreferenceGetterResponse(errors, preferences)
    }

    override val requestCodec = PreferenceGetterRequestCodec()

    override val responseCodec = PreferenceGetterResponseCodec()
}
