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

import android.app.PendingIntent
import android.app.slice.Slice
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
  // TODO: Remove note
  val note: CharSequence?,
) {
  companion object {
    // Copied over from jetpack
    const val SLICE_HINT_TYPE_DISPLAY_NAME =
            "androidx.credentials.provider.credentialEntry.SLICE_HINT_TYPE_DISPLAY_NAME"
    const val SLICE_HINT_USERNAME =
            "androidx.credentials.provider.credentialEntry.SLICE_HINT_USER_NAME"
    const val SLICE_HINT_DISPLAYNAME =
            "androidx.credentials.provider.credentialEntry.SLICE_HINT_CREDENTIAL_TYPE_DISPLAY_NAME"
    const val SLICE_HINT_LAST_USED_TIME_MILLIS =
            "androidx.credentials.provider.credentialEntry.SLICE_HINT_LAST_USED_TIME_MILLIS"
    const val SLICE_HINT_ICON =
            "androidx.credentials.provider.credentialEntry.SLICE_HINT_PROFILE_ICON"
    const val SLICE_HINT_PENDING_INTENT =
            "androidx.credentials.provider.credentialEntry.SLICE_HINT_PENDING_INTENT"

    /**
     * Returns an instance of [CredentialEntryUi] derived from a [Slice] object.
     *
     * @param slice the [Slice] object constructed through jetpack library
     */
    @JvmStatic
    fun fromSlice(slice: Slice): CredentialEntryUi {
      var username: CharSequence? = null
      var displayName: CharSequence = ""
      var icon: Icon? = null
      var pendingIntent: PendingIntent? = null
      var lastUsedTimeMillis: Long = 0
      var note: CharSequence? = null
      var typeDisplayName: CharSequence = ""

      slice.items.forEach {
        if (it.hasHint(SLICE_HINT_TYPE_DISPLAY_NAME)) {
          typeDisplayName = it.text
        } else if (it.hasHint(SLICE_HINT_USERNAME)) {
          username = it.text
        } else if (it.hasHint(SLICE_HINT_DISPLAYNAME)) {
          displayName = it.text
        } else if (it.hasHint(SLICE_HINT_ICON)) {
          icon = it.icon
        } else if (it.hasHint(SLICE_HINT_PENDING_INTENT)) {
          pendingIntent = it.action
        } else if (it.hasHint(SLICE_HINT_LAST_USED_TIME_MILLIS)) {
          lastUsedTimeMillis = it.long
        }
      }
      return CredentialEntryUi(
              slice.spec!!.type, typeDisplayName, username!!, displayName, icon,
              lastUsedTimeMillis, note,
      )
    }
  }
}
