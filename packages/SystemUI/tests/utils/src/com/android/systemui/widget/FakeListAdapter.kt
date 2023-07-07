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
 */

package com.android.systemui.widget

import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter

class FakeListAdapter(private var items: List<FakeListAdapterItem> = emptyList()) : BaseAdapter() {

    fun setItems(items: List<FakeListAdapterItem>) {
        this.items = items
        notifyDataSetChanged()
    }

    override fun getCount(): Int = items.size

    override fun getItem(position: Int): Any = items[position].data

    override fun getItemId(position: Int): Long = items[position].id

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View =
        items[position].view(position, convertView, parent)

    class FakeListAdapterItem(
        /** Result returned in [Adapter#getView] */
        val view: (position: Int, convertView: View?, parent: ViewGroup?) -> View,
        /** Returned in [Adapter#getItemId] */
        val id: Long = 0,
        /** Returned in [Adapter#getItem] */
        val data: Any = Unit,
    )
}
