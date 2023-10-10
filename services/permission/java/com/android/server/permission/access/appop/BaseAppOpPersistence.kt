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

import android.util.Slog
import com.android.modules.utils.BinaryXmlPullParser
import com.android.modules.utils.BinaryXmlSerializer
import com.android.server.permission.access.AccessState
import com.android.server.permission.access.MutableAccessState
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.immutable.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.util.attributeInt
import com.android.server.permission.access.util.attributeInterned
import com.android.server.permission.access.util.forEachTag
import com.android.server.permission.access.util.getAttributeIntOrThrow
import com.android.server.permission.access.util.getAttributeValueOrThrow
import com.android.server.permission.access.util.tag
import com.android.server.permission.access.util.tagName

abstract class BaseAppOpPersistence {
    abstract fun BinaryXmlPullParser.parseUserState(state: MutableAccessState, userId: Int)

    abstract fun BinaryXmlSerializer.serializeUserState(state: AccessState, userId: Int)

    protected fun BinaryXmlPullParser.parseAppOps(appOpModes: MutableIndexedMap<String, Int>) {
        forEachTag {
            when (tagName) {
                TAG_APP_OP -> parseAppOp(appOpModes)
                else -> Slog.w(LOG_TAG, "Ignoring unknown tag $name when parsing app-op state")
            }
        }
    }

    private fun BinaryXmlPullParser.parseAppOp(appOpModes: MutableIndexedMap<String, Int>) {
        val name = getAttributeValueOrThrow(ATTR_NAME).intern()
        val mode = getAttributeIntOrThrow(ATTR_MODE)
        appOpModes[name] = mode
    }

    protected fun BinaryXmlSerializer.serializeAppOps(appOpModes: IndexedMap<String, Int>) {
        appOpModes.forEachIndexed { _, name, mode -> serializeAppOp(name, mode) }
    }

    private fun BinaryXmlSerializer.serializeAppOp(name: String, mode: Int) {
        tag(TAG_APP_OP) {
            attributeInterned(ATTR_NAME, name)
            attributeInt(ATTR_MODE, mode)
        }
    }

    companion object {
        private val LOG_TAG = BaseAppOpPersistence::class.java.simpleName

        private const val TAG_APP_OP = "app-op"

        private const val ATTR_MODE = "mode"
        private const val ATTR_NAME = "name"
    }
}
