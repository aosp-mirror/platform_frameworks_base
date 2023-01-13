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
import android.util.Log
import androidx.annotation.VisibleForTesting

/**
 * UI representation for a credential entry used during the get credential flow.
 *
 * TODO: move to jetpack.
 */
class AuthenticationAction constructor(
        val pendingIntent: PendingIntent
) {


  companion object {
    private const val TAG = "AuthenticationAction"
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal const val SLICE_HINT_PENDING_INTENT =
            "androidx.credentials.provider.authenticationAction.SLICE_HINT_PENDING_INTENT"

    @JvmStatic
    fun fromSlice(slice: Slice): AuthenticationAction? {
      slice.items.forEach {
        if (it.hasHint(SLICE_HINT_PENDING_INTENT)) {
          return try {
            AuthenticationAction(it.action)
          } catch (e: Exception) {
            Log.i(TAG, "fromSlice failed with: " + e.message)
            null
          }
        }
      }
      return null
    }
  }
}
