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

package com.android.keyguard

import androidx.annotation.VisibleForTesting
import java.io.PrintWriter
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.collections.ArrayDeque

private val DEFAULT_FORMATTING = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

/** Queue for verbose logging checks for the listening state. */
class KeyguardListenQueue(
    val sizePerModality: Int = 20
) {
    private val faceQueue = ArrayDeque<KeyguardFaceListenModel>()
    private val fingerprintQueue = ArrayDeque<KeyguardFingerprintListenModel>()
    private val activeUnlockQueue = ArrayDeque<KeyguardActiveUnlockModel>()

    @get:VisibleForTesting val models: List<KeyguardListenModel>
        get() = faceQueue + fingerprintQueue + activeUnlockQueue

    /** Push a [model] to the queue (will be logged until the queue exceeds [sizePerModality]). */
    fun add(model: KeyguardListenModel) {
        val queue = when (model) {
            is KeyguardFaceListenModel -> faceQueue.apply { add(model) }
            is KeyguardFingerprintListenModel -> fingerprintQueue.apply { add(model) }
            is KeyguardActiveUnlockModel -> activeUnlockQueue.apply { add(model) }
        }

        if (queue.size > sizePerModality) {
            queue.removeFirstOrNull()
        }
    }

    /** Print verbose logs via the [writer]. */
    @JvmOverloads
    fun print(writer: PrintWriter, dateFormat: DateFormat = DEFAULT_FORMATTING) {
        val stringify: (KeyguardListenModel) -> String = { model ->
            "    ${dateFormat.format(Date(model.timeMillis))} $model"
        }

        writer.println("  Face listen results (last ${faceQueue.size} calls):")
        for (model in faceQueue) {
            writer.println(stringify(model))
        }
        writer.println("  Fingerprint listen results (last ${fingerprintQueue.size} calls):")
        for (model in fingerprintQueue) {
            writer.println(stringify(model))
        }
        writer.println("  Active unlock triggers (last ${activeUnlockQueue.size} calls):")
        for (model in activeUnlockQueue) {
            writer.println(stringify(model))
        }
    }
}
