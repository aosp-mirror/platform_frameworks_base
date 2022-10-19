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

package com.android.credentialmanager

import android.app.slice.Slice
import android.credentials.ui.Entry
import android.graphics.drawable.Icon

/**
 * UI representation for a save entry used during the create credential flow.
 *
 * TODO: move to jetpack.
 */
class SaveEntryUi(
  val title: CharSequence,
  val subTitle: CharSequence?,
  val icon: Icon?,
  val usageData: CharSequence?,
  // TODO: add
) {
  companion object {
    fun fromSlice(slice: Slice): SaveEntryUi {
      val items = slice.items

      var title: String? = null
      var subTitle: String? = null
      var icon: Icon? = null
      var usageData: String? = null

      items.forEach {
        if (it.hasHint(Entry.HINT_ICON)) {
          icon = it.icon
        } else if (it.hasHint(Entry.HINT_SUBTITLE) && it.subType == null) {
          subTitle = it.text.toString()
        } else if (it.hasHint(Entry.HINT_TITLE)) {
          title = it.text.toString()
        } else if (it.hasHint(Entry.HINT_SUBTITLE) && it.subType == Slice.SUBTYPE_MESSAGE) {
          usageData = it.text.toString()
        }
      }
      // TODO: fail NPE more elegantly.
      return SaveEntryUi(title!!, subTitle, icon, usageData)
    }
  }
}
