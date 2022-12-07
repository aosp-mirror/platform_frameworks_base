/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.permission.access

import android.util.AtomicFile
import android.util.Log
import com.android.modules.utils.BinaryXmlPullParser
import com.android.modules.utils.BinaryXmlSerializer
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.util.PermissionApex
import com.android.server.permission.access.util.parseBinaryXml
import com.android.server.permission.access.util.read
import com.android.server.permission.access.util.serializeBinaryXml
import com.android.server.permission.access.util.writeInlined
import java.io.File
import java.io.FileNotFoundException

class AccessPersistence(
    private val policy: AccessPolicy
) {
    fun read(state: AccessState) {
        readSystemState(state.systemState)
        val userStates = state.userStates
        state.systemState.userIds.forEachIndexed { _, userId ->
            readUserState(userId, userStates[userId])
        }
    }

    private fun readSystemState(systemState: SystemState) {
        systemFile.parse {
            // This is the canonical way to call an extension function in a different class.
            // TODO(b/259469752): Use context receiver for this when it becomes stable.
            with(policy) { parseSystemState(systemState) }
        }
    }

    private fun readUserState(userId: Int, userState: UserState) {
        getUserFile(userId).parse {
            with(policy) { parseUserState(userId, userState) }
        }
    }

    private inline fun File.parse(block: BinaryXmlPullParser.() -> Unit) {
        try {
            AtomicFile(this).read { it.parseBinaryXml(block) }
        } catch (e: FileNotFoundException) {
            Log.i(LOG_TAG, "$this not found")
        } catch (e: Exception) {
            throw IllegalStateException("Failed to read $this", e)
        }
    }

    fun write(state: AccessState) {
        writeState(state.systemState, ::writeSystemState)
        state.userStates.forEachIndexed { _, userId, userState ->
            writeState(userState) { writeUserState(userId, it) }
        }
    }

    private inline fun <T : WritableState> writeState(state: T, write: (T) -> Unit) {
        when (val writeMode = state.writeMode) {
            WriteMode.NONE -> {}
            WriteMode.SYNC -> write(state)
            WriteMode.ASYNC -> TODO()
            else -> error(writeMode)
        }
    }

    private fun writeSystemState(systemState: SystemState) {
        systemFile.serialize {
            with(policy) { serializeSystemState(systemState) }
        }
    }

    private fun writeUserState(userId: Int, userState: UserState) {
        getUserFile(userId).serialize {
            with(policy) { serializeUserState(userId, userState) }
        }
    }

    private inline fun File.serialize(block: BinaryXmlSerializer.() -> Unit) {
        try {
            AtomicFile(this).writeInlined { it.serializeBinaryXml(block) }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to serialize $this", e)
        }
    }

    private val systemFile: File
        get() = File(PermissionApex.systemDataDirectory, FILE_NAME)

    private fun getUserFile(userId: Int): File =
        File(PermissionApex.getUserDataDirectory(userId), FILE_NAME)

    companion object {
        private val LOG_TAG = AccessPersistence::class.java.simpleName

        private const val FILE_NAME = "access.abx"
    }
}
