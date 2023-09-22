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

package com.android.credentialmanager.model

import android.credentials.ui.ProviderData
import android.os.IBinder
import android.os.ResultReceiver
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap

/**
 * Represents the request made by the CredentialManager API.
 */
sealed class Request {

    /**
     * Request to close the app without displaying a message to the user and without reporting
     * anything back to the Credential Manager service.
     */
    data object Close : Request()

    /**
     * Request to close the app, displaying a message to the user.
     */
    data class Cancel(
        val showCancellationUi: Boolean,
        val appName: String
    ) : Request()

    /**
     * Request to start the get credentials flow.
     */
    data class Get(
        val token: IBinder?,
        val resultReceiver: ResultReceiver?,
        val providers: ImmutableMap<String, ProviderData>,
        val passwordEntries: ImmutableList<Password>,
    ) : Request()

    /**
     * Request to start the create credentials flow.
     */
    data object Create : Request()
}
