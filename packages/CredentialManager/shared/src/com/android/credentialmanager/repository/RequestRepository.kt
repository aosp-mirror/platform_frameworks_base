/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0N
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.credentialmanager.repository

import android.app.Application
import android.content.Intent
import android.util.Log
import com.android.credentialmanager.TAG
import com.android.credentialmanager.model.Request
import com.android.credentialmanager.parse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class RequestRepository(
    private val application: Application,
) {

    private val _requests = MutableStateFlow<Request?>(null)
    val requests: StateFlow<Request?> = _requests

    suspend fun processRequest(intent: Intent, previousIntent: Intent? = null) {
        val request = intent.parse(
            packageManager = application.packageManager,
            previousIntent = previousIntent
        )

        Log.d(TAG, "Request parsed: $request")

        _requests.value = request
    }
}
