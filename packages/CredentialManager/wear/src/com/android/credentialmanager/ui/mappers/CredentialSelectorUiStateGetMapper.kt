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

package com.android.credentialmanager.ui.mappers

import com.android.credentialmanager.model.Request
import com.android.credentialmanager.CredentialSelectorUiState

fun Request.Get.toGet(): CredentialSelectorUiState.Get {
    // TODO: b/301206470 returning a hard coded state for MVP
    if (true) return CredentialSelectorUiState.Get.SingleProviderSinglePassword

    return if (providerInfos.size == 1) {
        if (providerInfos.first().credentialEntryList.size == 1) {
            CredentialSelectorUiState.Get.SingleProviderSinglePassword
        } else {
            TODO() // b/301206470 - Implement other get flows
        }
    } else {
        TODO() // b/301206470 - Implement other get flows
    }
}
