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

package com.android.credentialmanager.jetpack.provider

import android.app.slice.Slice
import android.graphics.drawable.Icon

/**
 * UI representation for a credential entry used during the get credential flow.
 *
 * TODO: move to jetpack.
 */
abstract class CredentialEntryUi(
  val credentialTypeIcon: Icon,
  val profileIcon: Icon?,
  val lastUsedTimeMillis: Long?,
  val note: CharSequence?,
) {
  companion object {
    fun fromSlice(slice: Slice): CredentialEntryUi {
      return when (slice.spec?.type) {
        TYPE_PUBLIC_KEY_CREDENTIAL -> PasskeyCredentialEntryUi.fromSlice(slice)
        TYPE_PASSWORD_CREDENTIAL -> PasswordCredentialEntryUi.fromSlice(slice)
        else -> throw IllegalArgumentException("Unexpected type: ${slice.spec?.type}")
      }
    }

    const val TYPE_PUBLIC_KEY_CREDENTIAL: String =
      "androidx.credentials.TYPE_PUBLIC_KEY_CREDENTIAL"
    const val TYPE_PASSWORD_CREDENTIAL: String = "androidx.credentials.TYPE_PASSWORD"
  }
}
