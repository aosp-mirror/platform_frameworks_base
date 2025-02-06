/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.wm.shell.bubbles

import android.content.Intent
import android.content.pm.PackageManager

/**
 * Interface to check whether the activity backed by a specific intent is resizable.
 */
fun interface ResizabilityChecker {

    /**
     * Returns whether the provided intent represents a resizable activity.
     *
     * @param intent the intent to check
     * @param packageManager the package manager to use to do the look up
     * @param key a key representing thing being checked (used for error logging)
     */
    fun isResizableActivity(intent: Intent?, packageManager: PackageManager, key: String): Boolean
}