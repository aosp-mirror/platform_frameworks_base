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

import android.content.Intent
import android.credentials.ui.BaseDialogResult
import android.credentials.ui.ProviderPendingIntentResponse
import android.credentials.ui.UserSelectionDialogResult
import android.os.Bundle
import android.util.Log
import com.android.credentialmanager.TAG
import com.android.credentialmanager.model.Password
import com.android.credentialmanager.model.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PasswordRepository @Inject constructor() {

    suspend fun selectPassword(
        password: Password,
        request: Request.Get,
        resultCode: Int? = null,
        resultData: Intent? = null,
    ) {
        Log.d(TAG, "password selected: {provider=${password.providerId}" +
            ", key=${password.entry.key}, subkey=${password.entry.subkey}}")

        val userSelectionDialogResult = UserSelectionDialogResult(
            request.token,
            password.providerId,
            password.entry.key,
            password.entry.subkey,
            if (resultCode != null) ProviderPendingIntentResponse(resultCode, resultData) else null
        )
        val resultDataBundle = Bundle()
        UserSelectionDialogResult.addToBundle(userSelectionDialogResult, resultDataBundle)
        request.resultReceiver?.send(
            BaseDialogResult.RESULT_CODE_DIALOG_COMPLETE_WITH_SELECTION,
            resultDataBundle
        )
    }
}
