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

package com.android.credentialmanager

import android.content.Context
import android.content.SharedPreferences

class UserConfigRepo(context: Context) {
    val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        context.packageName, Context.MODE_PRIVATE)

    fun setDefaultProvider(
        providerId: String
    ) {
        sharedPreferences.edit().apply {
            putString(DEFAULT_PROVIDER, providerId)
            apply()
        }
    }

    fun setIsPasskeyFirstUse(
        isFirstUse: Boolean
    ) {
        sharedPreferences.edit().apply {
            putBoolean(IS_PASSKEY_FIRST_USE, isFirstUse)
            apply()
        }
    }

    fun getDefaultProviderId(): String? {
        return sharedPreferences.getString(DEFAULT_PROVIDER, null)
    }

    fun getIsPasskeyFirstUse(): Boolean {
        return sharedPreferences.getBoolean(IS_PASSKEY_FIRST_USE, true)
    }

    companion object {
        lateinit var repo: UserConfigRepo

        const val DEFAULT_PROVIDER = "default_provider"
        // This first use value only applies to passkeys, not related with if generally
        // credential manager is first use or not
        const val IS_PASSKEY_FIRST_USE = "is_passkey_first_use"

        fun setup(
            context: Context,
        ) {
            repo = UserConfigRepo(context)
        }

        fun getInstance(): UserConfigRepo {
            return repo
        }
    }
}
