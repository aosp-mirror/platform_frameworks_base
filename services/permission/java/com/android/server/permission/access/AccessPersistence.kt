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

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.os.UserHandle
import android.util.AtomicFile
import android.util.Slog
import android.util.SparseLongArray
import com.android.internal.annotations.GuardedBy
import com.android.modules.utils.BinaryXmlPullParser
import com.android.modules.utils.BinaryXmlSerializer
import com.android.server.IoThread
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.immutable.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.util.PermissionApex
import com.android.server.permission.access.util.parseBinaryXml
import com.android.server.permission.access.util.readWithReserveCopy
import com.android.server.permission.access.util.serializeBinaryXml
import com.android.server.permission.access.util.writeWithReserveCopy
import java.io.File
import java.io.FileNotFoundException

class AccessPersistence(private val policy: AccessPolicy) {
    private val scheduleLock = Any()
    @GuardedBy("scheduleLock") private val pendingMutationTimesMillis = SparseLongArray()
    @GuardedBy("scheduleLock") private val pendingStates = MutableIntMap<AccessState>()
    @GuardedBy("scheduleLock") private lateinit var writeHandler: WriteHandler

    private val writeLock = Any()

    fun initialize() {
        writeHandler = WriteHandler(IoThread.getHandler().looper)
    }

    /**
     * Reads the state either from the disk or migrate legacy data when the data files are missing.
     */
    fun read(state: MutableAccessState) {
        readSystemState(state)
        state.externalState.userIds.forEachIndexed { _, userId -> readUserState(state, userId) }
    }

    private fun readSystemState(state: MutableAccessState) {
        val fileExists =
            systemFile.parse {
                // This is the canonical way to call an extension function in a different class.
                // TODO(b/259469752): Use context receiver for this when it becomes stable.
                with(policy) { parseSystemState(state) }
            }

        if (!fileExists) {
            policy.migrateSystemState(state)
            state.systemState.write(state, UserHandle.USER_ALL)
        }
    }

    private fun readUserState(state: MutableAccessState, userId: Int) {
        val fileExists =
            getUserFile(userId).parse { with(policy) { parseUserState(state, userId) } }

        if (!fileExists) {
            policy.migrateUserState(state, userId)
            state.userStates[userId]!!.write(state, userId)
        }
    }

    /**
     * @return {@code true} if the file is successfully read from the disk; {@code false} if the
     *   file doesn't exist yet.
     */
    private inline fun File.parse(block: BinaryXmlPullParser.() -> Unit): Boolean =
        try {
            AtomicFile(this).readWithReserveCopy { it.parseBinaryXml(block) }
            true
        } catch (e: FileNotFoundException) {
            Slog.i(LOG_TAG, "$this not found")
            false
        } catch (e: Exception) {
            throw IllegalStateException("Failed to read $this", e)
        }

    fun write(state: AccessState) {
        state.systemState.write(state, UserHandle.USER_ALL)
        state.userStates.forEachIndexed { _, userId, userState -> userState.write(state, userId) }
    }

    private fun WritableState.write(state: AccessState, userId: Int) {
        when (val writeMode = writeMode) {
            WriteMode.NONE -> {}
            WriteMode.ASYNCHRONOUS -> {
                synchronized(scheduleLock) {
                    writeHandler.removeMessages(userId)
                    pendingStates[userId] = state
                    // SystemClock.uptimeMillis() is used in Handler.sendMessageDelayed().
                    val currentTimeMillis = SystemClock.uptimeMillis()
                    val pendingMutationTimeMillis =
                        pendingMutationTimesMillis.getOrPut(userId) { currentTimeMillis }
                    val currentDelayMillis = currentTimeMillis - pendingMutationTimeMillis
                    val message = writeHandler.obtainMessage(userId)
                    if (currentDelayMillis > MAX_WRITE_DELAY_MILLIS) {
                        message.sendToTarget()
                    } else {
                        val newDelayMillis =
                            WRITE_DELAY_TIME_MILLIS.coerceAtMost(
                                MAX_WRITE_DELAY_MILLIS - currentDelayMillis
                            )
                        writeHandler.sendMessageDelayed(message, newDelayMillis)
                    }
                }
            }
            WriteMode.SYNCHRONOUS -> {
                synchronized(scheduleLock) { pendingStates[userId] = state }
                writePendingState(userId)
            }
            else -> error(writeMode)
        }
    }

    private fun writePendingState(userId: Int) {
        synchronized(writeLock) {
            val state: AccessState?
            synchronized(scheduleLock) {
                pendingMutationTimesMillis -= userId
                state = pendingStates.remove(userId)
                writeHandler.removeMessages(userId)
            }
            if (state == null) {
                return
            }
            if (userId == UserHandle.USER_ALL) {
                writeSystemState(state)
            } else {
                writeUserState(state, userId)
            }
        }
    }

    private fun writeSystemState(state: AccessState) {
        systemFile.serialize { with(policy) { serializeSystemState(state) } }
    }

    private fun writeUserState(state: AccessState, userId: Int) {
        getUserFile(userId).serialize { with(policy) { serializeUserState(state, userId) } }
    }

    private inline fun File.serialize(block: BinaryXmlSerializer.() -> Unit) {
        try {
            AtomicFile(this).writeWithReserveCopy { it.serializeBinaryXml(block) }
        } catch (e: Exception) {
            Slog.e(LOG_TAG, "Failed to serialize $this", e)
        }
    }

    private val systemFile: File
        get() = File(PermissionApex.systemDataDirectory, FILE_NAME)

    private fun getUserFile(userId: Int): File =
        File(PermissionApex.getUserDataDirectory(userId), FILE_NAME)

    companion object {
        private val LOG_TAG = AccessPersistence::class.java.simpleName

        private const val FILE_NAME = "access.abx"

        private const val WRITE_DELAY_TIME_MILLIS = 1000L
        private const val MAX_WRITE_DELAY_MILLIS = 2000L
    }

    private inner class WriteHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(message: Message) {
            val userId = message.what
            writePendingState(userId)
        }
    }
}
