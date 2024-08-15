/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.credentialmanager.common

class Constants {
    companion object Constants {
        const val LOG_TAG = "CredentialSelector"
        const val BUNDLE_KEY_PREFER_IMMEDIATELY_AVAILABLE_CREDENTIALS =
            "androidx.credentials.BUNDLE_KEY_IS_AUTO_SELECT_ALLOWED"
        const val IS_AUTO_SELECTED_KEY = "IS_AUTO_SELECTED"
        const val BIOMETRIC_AUTH_RESULT = "androidx.credentials.provider.BIOMETRIC_AUTH_RESULT"
        const val BIOMETRIC_AUTH_ERROR_CODE =
                "androidx.credentials.provider.BIOMETRIC_AUTH_ERROR_CODE"
        const val BIOMETRIC_AUTH_ERROR_MESSAGE =
                "androidx.credentials.provider.BIOMETRIC_AUTH_ERROR_MESSAGE"
        const val BIOMETRIC_FRAMEWORK_OPTION =
                "androidx.credentials.provider.BIOMETRIC_FRAMEWORK_OPTION"
    }
}
