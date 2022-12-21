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

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.slice.Slice
import android.app.slice.SliceSpec
import android.graphics.drawable.Icon
import android.net.Uri
import android.util.Log
import java.util.Collections

/**
 * UI representation for a credential entry used during the get credential flow.
 *
 * TODO: move to jetpack.
 */
open class CredentialEntry constructor(
        // TODO("Add credential type display name for both CredentialEntry & CreateEntry")
        val type: String,
        val typeDisplayName: CharSequence,
        val username: CharSequence,
        val displayName: CharSequence?,
        val pendingIntent: PendingIntent?,
        // TODO("Consider using Instant or other strongly typed time data type")
        val lastUsedTimeMillis: Long,
        val icon: Icon?,
        var autoSelectAllowed: Boolean
) {
  init {
    require(type.isNotEmpty()) { "type must not be empty" }
    require(username.isNotEmpty()) { "type must not be empty" }
  }

  companion object {
    private const val TAG = "CredentialEntry"
    internal const val SLICE_HINT_TYPE_DISPLAY_NAME =
            "androidx.credentials.provider.credentialEntry.SLICE_HINT_TYPE_DISPLAY_NAME"
    internal const val SLICE_HINT_USERNAME =
            "androidx.credentials.provider.credentialEntry.SLICE_HINT_USER_NAME"
    internal const val SLICE_HINT_DISPLAYNAME =
            "androidx.credentials.provider.credentialEntry.SLICE_HINT_CREDENTIAL_TYPE_DISPLAY_NAME"
    internal const val SLICE_HINT_LAST_USED_TIME_MILLIS =
            "androidx.credentials.provider.credentialEntry.SLICE_HINT_LAST_USED_TIME_MILLIS"
    internal const val SLICE_HINT_ICON =
            "androidx.credentials.provider.credentialEntry.SLICE_HINT_PROFILE_ICON"
    internal const val SLICE_HINT_PENDING_INTENT =
            "androidx.credentials.provider.credentialEntry.SLICE_HINT_PENDING_INTENT"
    internal const val SLICE_HINT_AUTO_ALLOWED =
            "androidx.credentials.provider.credentialEntry.SLICE_HINT_AUTO_ALLOWED"
    internal const val AUTO_SELECT_TRUE_STRING = "true"
    internal const val AUTO_SELECT_FALSE_STRING = "false"

    @JvmStatic
    internal fun toSlice(credentialEntry: CredentialEntry): Slice {
      // TODO("Put the right revision value")
      val autoSelectAllowed = if (credentialEntry.autoSelectAllowed) {
        AUTO_SELECT_TRUE_STRING
      } else {
        AUTO_SELECT_FALSE_STRING
      }
      val sliceBuilder = Slice.Builder(Uri.EMPTY, SliceSpec(
              credentialEntry.type, 1))
              .addText(credentialEntry.typeDisplayName, /*subType=*/null,
                      listOf(SLICE_HINT_TYPE_DISPLAY_NAME))
              .addText(credentialEntry.username, /*subType=*/null,
                      listOf(SLICE_HINT_USERNAME))
              .addText(credentialEntry.displayName, /*subType=*/null,
                      listOf(SLICE_HINT_DISPLAYNAME))
              .addLong(credentialEntry.lastUsedTimeMillis, /*subType=*/null,
                      listOf(SLICE_HINT_LAST_USED_TIME_MILLIS))
              .addText(autoSelectAllowed, /*subType=*/null,
                      listOf(SLICE_HINT_AUTO_ALLOWED))
      if (credentialEntry.icon != null) {
        sliceBuilder.addIcon(credentialEntry.icon, /*subType=*/null,
                listOf(SLICE_HINT_ICON))
      }
      if (credentialEntry.pendingIntent != null) {
        sliceBuilder.addAction(credentialEntry.pendingIntent,
                Slice.Builder(sliceBuilder)
                        .addHints(Collections.singletonList(SLICE_HINT_PENDING_INTENT))
                        .build(),
                /*subType=*/null)
      }
      return sliceBuilder.build()
    }

    /**
     * Returns an instance of [CredentialEntry] derived from a [Slice] object.
     *
     * @param slice the [Slice] object constructed through [toSlice]
     */
    @SuppressLint("WrongConstant") // custom conversion between jetpack and framework
    @JvmStatic
    fun fromSlice(slice: Slice): CredentialEntry? {
      var typeDisplayName: CharSequence? = null
      var username: CharSequence? = null
      var displayName: CharSequence? = null
      var icon: Icon? = null
      var pendingIntent: PendingIntent? = null
      var lastUsedTimeMillis: Long = 0
      var autoSelectAllowed = false

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
        } else if (it.hasHint(SLICE_HINT_AUTO_ALLOWED)) {
          val autoSelectValue = it.text
          if (autoSelectValue == AUTO_SELECT_TRUE_STRING) {
            autoSelectAllowed = true
          }
        }
      }

      return try {
        CredentialEntry(slice.spec!!.type, typeDisplayName!!, username!!,
                displayName, pendingIntent,
                lastUsedTimeMillis, icon, autoSelectAllowed)
      } catch (e: Exception) {
        Log.i(TAG, "fromSlice failed with: " + e.message)
        null
      }
    }
  }
}
