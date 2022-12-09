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

package com.android.systemui.settings

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Interface for retrieving file paths for file storage of system and secondary users.
 */
interface UserFileManager {
    /**
     * Return the file based on current user.
     */
    fun getFile(fileName: String, userId: Int): File
    /**
     * Get shared preferences from user.
     */
    fun getSharedPreferences(
        fileName: String,
        @Context.PreferencesMode mode: Int,
        userId: Int
    ): SharedPreferences
}
