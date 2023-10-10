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

import android.os.Process
import android.util.Slog
import com.android.modules.utils.BinaryXmlPullParser
import com.android.modules.utils.BinaryXmlSerializer
import com.android.server.permission.access.AccessState
import com.android.server.permission.access.AppIdAppOpModes
import com.android.server.permission.access.MutableAccessState
import com.android.server.permission.access.MutableAppIdAppOpModes
import com.android.server.permission.access.WriteMode
import com.android.server.permission.access.immutable.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.util.attributeInt
import com.android.server.permission.access.util.forEachTag
import com.android.server.permission.access.util.getAttributeIntOrThrow
import com.android.server.permission.access.util.tag
import com.android.server.permission.access.util.tagName

class AppIdAppOpPersistence : BaseAppOpPersistence() {
    override fun BinaryXmlPullParser.parseUserState(state: MutableAccessState, userId: Int) {
        when (tagName) {
            TAG_APP_ID_APP_OPS -> parseAppIdAppOps(state, userId)
            else -> {}
        }
    }

    private fun BinaryXmlPullParser.parseAppIdAppOps(state: MutableAccessState, userId: Int) {
        val userState = state.mutateUserState(userId, WriteMode.NONE)!!
        val appIdAppOpModes = userState.mutateAppIdAppOpModes()
        forEachTag {
            when (tagName) {
                TAG_APP_ID -> parseAppId(appIdAppOpModes)
                else -> Slog.w(LOG_TAG, "Ignoring unknown tag $name when parsing app-op state")
            }
        }
        userState.appIdAppOpModes.forEachReversedIndexed { appIdIndex, appId, _ ->
            // Non-application UIDs may not have an Android package but may still have app op state.
            if (
                appId !in state.externalState.appIdPackageNames &&
                    appId >= Process.FIRST_APPLICATION_UID
            ) {
                Slog.w(LOG_TAG, "Dropping unknown app ID $appId when parsing app-op state")
                appIdAppOpModes.removeAt(appIdIndex)
                userState.requestWriteMode(WriteMode.ASYNCHRONOUS)
            }
        }
    }

    private fun BinaryXmlPullParser.parseAppId(appIdAppOpModes: MutableAppIdAppOpModes) {
        val appId = getAttributeIntOrThrow(ATTR_ID)
        val appOpModes = MutableIndexedMap<String, Int>()
        appIdAppOpModes[appId] = appOpModes
        parseAppOps(appOpModes)
    }

    override fun BinaryXmlSerializer.serializeUserState(state: AccessState, userId: Int) {
        serializeAppIdAppOps(state.userStates[userId]!!.appIdAppOpModes)
    }

    private fun BinaryXmlSerializer.serializeAppIdAppOps(appIdAppOpModes: AppIdAppOpModes) {
        tag(TAG_APP_ID_APP_OPS) {
            appIdAppOpModes.forEachIndexed { _, appId, appOpModes ->
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
