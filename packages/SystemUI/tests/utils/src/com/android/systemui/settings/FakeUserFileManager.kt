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

package com.android.systemui.settings

import android.content.SharedPreferences
import com.android.systemui.util.FakeSharedPreferences
import java.io.File

class FakeUserFileManager : UserFileManager {
    private val sharedPreferences = mutableMapOf<SharedPrefKey, FakeSharedPreferences>()

    override fun getFile(fileName: String, userId: Int): File {
        throw UnsupportedOperationException("getFile not implemented in fake")
    }

    override fun getSharedPreferences(fileName: String, mode: Int, userId: Int): SharedPreferences {
        val key = SharedPrefKey(fileName, mode, userId)
        return sharedPreferences.getOrPut(key) { FakeSharedPreferences() }
    }

    private data class SharedPrefKey(val fileName: String, val mode: Int, val userId: Int)
}
