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
import com.android.server.permission.access.util.attributeInterned
import com.android.server.permission.access.util.forEachTag
import com.android.server.permission.access.util.getAttributeValueOrThrow
import com.android.server.permission.access.util.tag
import com.android.server.permission.access.util.tagName

class PackageAppOpPersistence : BaseAppOpPersistence() {
    override fun BinaryXmlPullParser.parseUserState(state: AccessState, userId: Int) {
        when (tagName) {
            TAG_PACKAGE_APP_OPS -> parsePackageAppOps(state, userId)
            else -> {}
        }
    }

    private fun BinaryXmlPullParser.parsePackageAppOps(state: AccessState, userId: Int) {
        val userState = state.userStates[userId]
        forEachTag {
            when (tagName) {
                TAG_PACKAGE -> parsePackage(userState)
                else -> Log.w(LOG_TAG, "Ignoring unknown tag $name when parsing app-op state")
            }
        }
        userState.packageAppOpModes.retainAllIndexed { _, packageName, _ ->
            val hasPackage = packageName in state.systemState.packageStates
            if (!hasPackage) {
                Log.w(LOG_TAG, "Dropping unknown package $packageName when parsing app-op state")
            }
            hasPackage
        }
    }

    private fun BinaryXmlPullParser.parsePackage(userState: UserState) {
        val packageName = getAttributeValueOrThrow(ATTR_NAME).intern()
        val appOpModes = IndexedMap<String, Int>()
        userState.packageAppOpModes[packageName] = appOpModes
        parseAppOps(appOpModes)
    }

    override fun BinaryXmlSerializer.serializeUserState(state: AccessState, userId: Int) {
        serializePackageAppOps(state.userStates[userId])
    }

    private fun BinaryXmlSerializer.serializePackageAppOps(userState: UserState) {
        tag(TAG_PACKAGE_APP_OPS) {
            userState.packageAppOpModes.forEachIndexed { _, packageName, appOpModes ->
                serializePackage(packageName, appOpModes)
            }
        }
    }

    private fun BinaryXmlSerializer.serializePackage(
        packageName: String,
        appOpModes: IndexedMap<String, Int>
    ) {
        tag(TAG_PACKAGE) {
            attributeInterned(ATTR_NAME, packageName)
            serializeAppOps(appOpModes)
        }
    }

    companion object {
        private val LOG_TAG = PackageAppOpPersistence::class.java.simpleName

        private const val TAG_PACKAGE = "package"
        private const val TAG_PACKAGE_APP_OPS = "package-app-ops"

        private const val ATTR_NAME = "name"
    }
}
