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
 * UI representation for a credential entry used during the get credential flow.
 *
 * TODO: move to jetpack.
 */
class CredentialEntryUi(
  val credentialType: CharSequence,
  val credentialTypeDisplayName: CharSequence,
  val userName: CharSequence,
  val userDisplayName: CharSequence?,
  val entryIcon: Icon?,
  val lastUsedTimeMillis: Long?,
  val note: CharSequence?,
) {
  companion object {
    fun fromSlice(slice: Slice): CredentialEntryUi {
      var credentialType = slice.spec!!.type
      var credentialTypeDisplayName: CharSequence? = null
      var userName: CharSequence? = null
      var userDisplayName: CharSequence? = null
      var entryIcon: Icon? = null
      var lastUsedTimeMillis: Long? = null
      var note: CharSequence? = null

      val items = slice.items
      items.forEach {
        if (it.hasHint(Entry.HINT_CREDENTIAL_TYPE_DISPLAY_NAME)) {
          credentialTypeDisplayName = it.text
        } else if (it.hasHint(Entry.HINT_USER_NAME)) {
          userName = it.text
        } else if (it.hasHint(Entry.HINT_PASSKEY_USER_DISPLAY_NAME)) {
          userDisplayName = it.text
        } else if (it.hasHint(Entry.HINT_PROFILE_ICON)) {
          entryIcon = it.icon
        } else if (it.hasHint(Entry.HINT_LAST_USED_TIME_MILLIS)) {
          lastUsedTimeMillis = it.long
        } else if (it.hasHint(Entry.HINT_NOTE)) {
          note = it.text
        }
      }

      return CredentialEntryUi(
        credentialType, credentialTypeDisplayName!!, userName!!, userDisplayName, entryIcon,
        lastUsedTimeMillis, note,
      )
    }
  }
}
