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
 * UI representation for a save entry used during the create credential flow.
 *
 * TODO: move to jetpack.
 */
class SaveEntryUi(
  val userProviderAccountName: CharSequence?,
  val credentialTypeIcon: Icon?,
  val profileIcon: Icon?,
  val passwordCount: Int?,
  val passkeyCount: Int?,
  val totalCredentialCount: Int?,
  val lastUsedTimeMillis: Long?,
) {
  companion object {
    const val SLICE_HINT_ACCOUNT_NAME =
            "androidx.credentials.provider.createEntry.SLICE_HINT_USER_PROVIDER_ACCOUNT_NAME"
    const val SLICE_HINT_ICON =
            "androidx.credentials.provider.createEntry.SLICE_HINT_PROFILE_ICON"
    const val SLICE_HINT_CREDENTIAL_COUNT_INFORMATION =
            "androidx.credentials.provider.createEntry.SLICE_HINT_CREDENTIAL_COUNT_INFORMATION"
    const val SLICE_HINT_LAST_USED_TIME_MILLIS =
            "androidx.credentials.provider.createEntry.SLICE_HINT_LAST_USED_TIME_MILLIS"
    const val SLICE_HINT_PENDING_INTENT =
            "androidx.credentials.provider.createEntry.SLICE_HINT_PENDING_INTENT"

    /**
     * Returns an instance of [SaveEntryUi] derived from a [Slice] object.
     *
     * @param slice the [Slice] object constructed through the jetpack library
     */
    @JvmStatic
    fun fromSlice(slice: Slice): SaveEntryUi {
      var accountName: CharSequence? = null
      var icon: Icon? = null
      var pendingIntent: PendingIntent? = null
      var lastUsedTimeMillis: Long = 0

      slice.items.forEach {
        if (it.hasHint(SLICE_HINT_ACCOUNT_NAME)) {
          accountName = it.text
        } else if (it.hasHint(SLICE_HINT_ICON)) {
          icon = it.icon
        } else if (it.hasHint(SLICE_HINT_PENDING_INTENT)) {
          pendingIntent = it.action
        } else if (it.hasHint(SLICE_HINT_LAST_USED_TIME_MILLIS)) {
          lastUsedTimeMillis = it.long
        }
      }

      return SaveEntryUi(
              // TODO: Add count parsing
              accountName!!, icon, icon,
              0, 0, 0, lastUsedTimeMillis,
      )
    }
  }
}
