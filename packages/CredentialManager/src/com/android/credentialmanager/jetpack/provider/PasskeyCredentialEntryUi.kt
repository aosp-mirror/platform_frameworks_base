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

class PasskeyCredentialEntryUi(
  val userName: CharSequence,
  val userDisplayName: CharSequence?,
  credentialTypeIcon: Icon,
  profileIcon: Icon?,
  lastUsedTimeMillis: Long?,
  note: CharSequence?,
) : CredentialEntryUi(credentialTypeIcon, profileIcon, lastUsedTimeMillis, note) {
  companion object {
    fun fromSlice(slice: Slice): CredentialEntryUi {
      var userName: CharSequence? = null
      var userDisplayName: CharSequence? = null
      var credentialTypeIcon: Icon? = null
      var profileIcon: Icon? = null
      var lastUsedTimeMillis: Long? = null
      var note: CharSequence? = null

      val items = slice.items
      items.forEach {
        if (it.hasHint(Entry.HINT_USER_NAME)) {
          userName = it.text
        } else if (it.hasHint(Entry.HINT_PASSKEY_USER_DISPLAY_NAME)) {
          userDisplayName = it.text
        } else if (it.hasHint(Entry.HINT_CREDENTIAL_TYPE_ICON)) {
          credentialTypeIcon = it.icon
        } else if (it.hasHint(Entry.HINT_PROFILE_ICON)) {
          profileIcon = it.icon
        } else if (it.hasHint(Entry.HINT_LAST_USED_TIME_MILLIS)) {
          lastUsedTimeMillis = it.long
        } else if (it.hasHint(Entry.HINT_NOTE)) {
          note = it.text
        }
      }
      // TODO: fail NPE more elegantly.
      return PasskeyCredentialEntryUi(
        userName!!, userDisplayName, credentialTypeIcon!!,
        profileIcon, lastUsedTimeMillis, note,
      )
    }
  }
}
