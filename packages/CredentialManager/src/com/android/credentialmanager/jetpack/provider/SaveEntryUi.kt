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
import android.credentials.ui.Entry
import android.graphics.drawable.Icon

/**
 * UI representation for a save entry used during the create credential flow.
 *
 * TODO: move to jetpack.
 */
class SaveEntryUi(
  val userProviderAccountName: CharSequence,
  val credentialTypeIcon: Icon?,
  val profileIcon: Icon?,
  val passwordCount: Int?,
  val passkeyCount: Int?,
  val totalCredentialCount: Int?,
  val lastUsedTimeMillis: Long?,
) {
  companion object {
    fun fromSlice(slice: Slice): SaveEntryUi {
      var userProviderAccountName: CharSequence? = null
      var credentialTypeIcon: Icon? = null
      var profileIcon: Icon? = null
      var passwordCount: Int? = null
      var passkeyCount: Int? = null
      var totalCredentialCount: Int? = null
      var lastUsedTimeMillis: Long? = null


      val items = slice.items
      items.forEach {
        if (it.hasHint(Entry.HINT_USER_PROVIDER_ACCOUNT_NAME)) {
          userProviderAccountName = it.text
        } else if (it.hasHint(Entry.HINT_CREDENTIAL_TYPE_ICON)) {
          credentialTypeIcon = it.icon
        } else if (it.hasHint(Entry.HINT_PROFILE_ICON)) {
          profileIcon = it.icon
        } else if (it.hasHint(Entry.HINT_PASSWORD_COUNT)) {
          passwordCount = it.int
        } else if (it.hasHint(Entry.HINT_PASSKEY_COUNT)) {
          passkeyCount = it.int
        } else if (it.hasHint(Entry.HINT_TOTAL_CREDENTIAL_COUNT)) {
          totalCredentialCount = it.int
        } else if (it.hasHint(Entry.HINT_LAST_USED_TIME_MILLIS)) {
          lastUsedTimeMillis = it.long
        }
      }
      // TODO: fail NPE more elegantly.
      return SaveEntryUi(
        userProviderAccountName!!, credentialTypeIcon, profileIcon,
        passwordCount, passkeyCount, totalCredentialCount, lastUsedTimeMillis,
      )
    }
  }
}
