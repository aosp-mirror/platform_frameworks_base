/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.permission.access.appop

import android.util.Log
import com.android.modules.utils.BinaryXmlPullParser
import com.android.modules.utils.BinaryXmlSerializer
import com.android.server.permission.access.AccessState
import com.android.server.permission.access.UserState
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.util.attributeInt
import com.android.server.permission.access.util.forEachTag
import com.android.server.permission.access.util.getAttributeIntOrThrow
import com.android.server.permission.access.util.tag
import com.android.server.permission.access.util.tagName

class AppIdAppOpPersistence : BaseAppOpPersistence() {
    override fun BinaryXmlPullParser.parseUserState(state: AccessState, userId: Int) {
        when (tagName) {
            TAG_APP_ID_APP_OPS -> parseAppIdAppOps(state, userId)
            else -> {}
        }
    }

    private fun BinaryXmlPullParser.parseAppIdAppOps(state: AccessState, userId: Int) {
        val userState = state.userStates[userId]
        forEachTag {
            when (tagName) {
                TAG_APP_ID -> parseAppId(userState)
                else -> Log.w(LOG_TAG, "Ignoring unknown tag $name when parsing app-op state")
            }
        }
        userState.appIdAppOpModes.retainAllIndexed { _, appId, _ ->
            val hasAppId = appId in state.systemState.appIds
            if (!hasAppId) {
                Log.w(LOG_TAG, "Dropping unknown app ID $appId when parsing app-op state")
            }
            hasAppId
        }
    }

    private fun BinaryXmlPullParser.parseAppId(userState: UserState) {
        val appId = getAttributeIntOrThrow(ATTR_ID)
        val appOpModes = IndexedMap<String, Int>()
        userState.appIdAppOpModes[appId] = appOpModes
        parseAppOps(appOpModes)
    }

    override fun BinaryXmlSerializer.serializeUserState(state: AccessState, userId: Int) {
        serializeAppIdAppOps(state.userStates[userId])
    }

    private fun BinaryXmlSerializer.serializeAppIdAppOps(userState: UserState) {
        tag(TAG_APP_ID_APP_OPS) {
            userState.appIdAppOpModes.forEachIndexed { _, appId, appOpModes ->
                serializeAppId(appId, appOpModes)
            }
        }
    }

    private fun BinaryXmlSerializer.serializeAppId(
        appId: Int,
        appOpModes: IndexedMap<String, Int>
    ) {
        tag(TAG_APP_ID) {
            attributeInt(ATTR_ID, appId)
            serializeAppOps(appOpModes)
        }
    }

    companion object {
        private val LOG_TAG = AppIdAppOpPersistence::class.java.simpleName

        private const val TAG_APP_ID = "app-id"
        private const val TAG_APP_ID_APP_OPS = "app-id-app-ops"

        private const val ATTR_ID = "id"
    }
}
