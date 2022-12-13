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

/**
 * UI representation for a credential entry used during the get credential flow.
 *
 * TODO: move to jetpack.
 */
class ActionUi(
  val text: CharSequence,
  val subtext: CharSequence?,
) {
  companion object {
    fun fromSlice(slice: Slice): ActionUi {
      var text: CharSequence? = null
      var subtext: CharSequence? = null

      val items = slice.items
      items.forEach {
        if (it.hasHint(Entry.HINT_ACTION_TITLE)) {
          text = it.text
        } else if (it.hasHint(Entry.HINT_ACTION_SUBTEXT)) {
          subtext = it.text
        }
      }
      // TODO: fail NPE more elegantly.
      return ActionUi(text!!, subtext)
    }
  }
}
