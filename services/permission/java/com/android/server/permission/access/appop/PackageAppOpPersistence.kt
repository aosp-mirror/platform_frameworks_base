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
import com.android.server.permission.access.UserState
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.util.attributeInterned
import com.android.server.permission.access.util.forEachTag
import com.android.server.permission.access.util.getAttributeValueOrThrow
import com.android.server.permission.access.util.tag
import com.android.server.permission.access.util.tagName

class PackageAppOpPersistence : BaseAppOpPersistence() {
    override fun BinaryXmlPullParser.parseUserState(userId: Int, userState: UserState) {
        when (tagName) {
            TAG_PACKAGE_APP_OPS -> parsePackageAppOps(userState)
            else -> {}
        }
    }

    private fun BinaryXmlPullParser.parsePackageAppOps(userState: UserState) {
        forEachTag {
            when (tagName) {
                TAG_PACKAGE -> parsePackage(userState)
                else -> Log.w(LOG_TAG, "Ignoring unknown tag $name when parsing app-op state")
            }
        }
    }

    private fun BinaryXmlPullParser.parsePackage(userState: UserState) {
        val packageName = getAttributeValueOrThrow(ATTR_NAME).intern()
        val appOpModes = IndexedMap<String, Int>()
        userState.packageAppOpModes[packageName] = appOpModes
        parseAppOps(appOpModes)
    }

    override fun BinaryXmlSerializer.serializeUserState(userId: Int, userState: UserState) {
        serializePackageAppOps(userState)
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

        private const val TAG_PACKAGE_APP_OPS = "package-app-ops"
        private const val TAG_PACKAGE = "package"

        private const val ATTR_NAME = "name"
    }
}
