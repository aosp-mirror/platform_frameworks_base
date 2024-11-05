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

package com.android.settingslib.ipc

import android.os.Bundle

/** [MessageCodec] for [Int]. */
object IntMessageCodec : MessageCodec<Int> {
    override fun encode(data: Int): Bundle = Bundle(1).apply { putInt(null, data) }

    override fun decode(data: Bundle): Int = data.getInt(null)
}

/** [MessageCodec] for [Set<Int>]. */
class IntSetMessageCodec : MessageCodec<Set<Int>> {
    override fun encode(data: Set<Int>): Bundle =
        Bundle(1).apply { putIntArray(null, data.toIntArray()) }

    override fun decode(data: Bundle): Set<Int> = data.getIntArray(null)?.toSet() ?: setOf()
}

/** [MessageCodec] for [Set<String>]. */
class StringSetMessageCodec : MessageCodec<Set<String>> {
    override fun encode(data: Set<String>): Bundle =
        Bundle(1).apply { putStringArray(null, data.toTypedArray()) }

    override fun decode(data: Bundle): Set<String> = data.getStringArray(null)?.toSet() ?: setOf()
}
