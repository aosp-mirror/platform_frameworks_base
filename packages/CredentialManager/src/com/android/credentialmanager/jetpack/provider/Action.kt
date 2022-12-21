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
import android.net.Uri
import android.util.Log
import java.util.Collections

/**
 * UI representation for a credential entry used during the get credential flow.
 *
 * TODO: move to jetpack.
 */
class Action constructor(
        val title: CharSequence,
        val subTitle: CharSequence?,
        val pendingIntent: PendingIntent?,
) {

  init {
    require(title.isNotEmpty()) { "title must not be empty" }
  }

  companion object {
    private const val TAG = "Action"
    internal const val SLICE_HINT_TITLE =
            "androidx.credentials.provider.action.HINT_ACTION_TITLE"
    internal const val SLICE_HINT_SUBTITLE =
            "androidx.credentials.provider.action.HINT_ACTION_SUBTEXT"
    internal const val SLICE_HINT_PENDING_INTENT =
            "androidx.credentials.provider.action.SLICE_HINT_PENDING_INTENT"

    @JvmStatic
    fun toSlice(action: Action): Slice {
      // TODO("Put the right spec and version value")
      val sliceBuilder = Slice.Builder(Uri.EMPTY, SliceSpec("type", 1))
              .addText(action.title, /*subType=*/null,
                      listOf(SLICE_HINT_TITLE))
              .addText(action.subTitle, /*subType=*/null,
                      listOf(SLICE_HINT_SUBTITLE))
      if (action.pendingIntent != null) {
        sliceBuilder.addAction(action.pendingIntent,
                Slice.Builder(sliceBuilder)
                        .addHints(Collections.singletonList(SLICE_HINT_PENDING_INTENT))
                        .build(),
                /*subType=*/null)
      }
      return sliceBuilder.build()
    }

    /**
     * Returns an instance of [Action] derived from a [Slice] object.
     *
     * @param slice the [Slice] object constructed through [toSlice]
     */
    @SuppressLint("WrongConstant") // custom conversion between jetpack and framework
    @JvmStatic
    fun fromSlice(slice: Slice): Action? {
      // TODO("Put the right spec and version value")
      var title: CharSequence = ""
      var subTitle: CharSequence? = null
      var pendingIntent: PendingIntent? = null

      slice.items.forEach {
        if (it.hasHint(SLICE_HINT_TITLE)) {
          title = it.text
        } else if (it.hasHint(SLICE_HINT_SUBTITLE)) {
          subTitle = it.text
        } else if (it.hasHint(SLICE_HINT_PENDING_INTENT)) {
          pendingIntent = it.action
        }
      }

      return try {
        Action(title, subTitle, pendingIntent)
      } catch (e: Exception) {
        Log.i(TAG, "fromSlice failed with: " + e.message)
        null
      }
    }
  }
}
