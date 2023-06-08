/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.controls.ui

import android.content.Context
import android.widget.ArrayAdapter
import androidx.annotation.LayoutRes

open class OverflowMenuAdapter(
    context: Context,
    @LayoutRes layoutId: Int,
    itemsWithIds: List<MenuItem>,
    private val isEnabledInternal: OverflowMenuAdapter.(Int) -> Boolean
) : ArrayAdapter<CharSequence>(context, layoutId, itemsWithIds.map(MenuItem::text)) {

    private val ids = itemsWithIds.map(MenuItem::id)

    override fun getItemId(position: Int): Long {
        return ids[position]
    }

    override fun isEnabled(position: Int): Boolean {
        return isEnabledInternal(position)
    }

    data class MenuItem(val text: CharSequence, val id: Long)
}
