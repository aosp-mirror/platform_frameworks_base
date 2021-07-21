/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.test.silkfx

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseExpandableListAdapter
import android.widget.ExpandableListView
import android.widget.TextView
import com.android.test.silkfx.app.CommonDemoActivity
import com.android.test.silkfx.app.EXTRA_LAYOUT
import com.android.test.silkfx.app.EXTRA_TITLE
import com.android.test.silkfx.hdr.GlowActivity
import com.android.test.silkfx.materials.GlassActivity
import kotlin.reflect.KClass

class Demo(val name: String, val makeIntent: (Context) -> Intent) {
    constructor(name: String, activity: KClass<out Activity>) : this(name, { context ->
        Intent(context, activity.java)
    })
    constructor(name: String, layout: Int) : this(name, { context ->
        Intent(context, CommonDemoActivity::class.java).apply {
            putExtra(EXTRA_LAYOUT, layout)
            putExtra(EXTRA_TITLE, name)
        }
    })
}
data class DemoGroup(val groupName: String, val demos: List<Demo>)

private val AllDemos = listOf(
        DemoGroup("HDR", listOf(
                Demo("Glow", GlowActivity::class),
                Demo("Blingy Notifications", R.layout.bling_notifications)
        )),
        DemoGroup("Materials", listOf(
                Demo("Glass", GlassActivity::class)
        ))
)

class Main : Activity() {

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val list = ExpandableListView(this)

        setContentView(list)

        val inflater = LayoutInflater.from(this)
        list.setAdapter(object : BaseExpandableListAdapter() {
            override fun getGroup(groupPosition: Int): DemoGroup {
                return AllDemos[groupPosition]
            }

            override fun isChildSelectable(groupPosition: Int, childPosition: Int): Boolean = true

            override fun hasStableIds(): Boolean = true

            override fun getGroupView(
                groupPosition: Int,
                isExpanded: Boolean,
                convertView: View?,
                parent: ViewGroup?
            ): View {
                val view = (convertView ?: inflater.inflate(
                        android.R.layout.simple_expandable_list_item_1, parent, false)) as TextView
                view.text = AllDemos[groupPosition].groupName
                return view
            }

            override fun getChildrenCount(groupPosition: Int): Int {
                return AllDemos[groupPosition].demos.size
            }

            override fun getChild(groupPosition: Int, childPosition: Int): Demo {
                return AllDemos[groupPosition].demos[childPosition]
            }

            override fun getGroupId(groupPosition: Int): Long = groupPosition.toLong()

            override fun getChildView(
                groupPosition: Int,
                childPosition: Int,
                isLastChild: Boolean,
                convertView: View?,
                parent: ViewGroup?
            ): View {
                val view = (convertView ?: inflater.inflate(
                        android.R.layout.simple_expandable_list_item_1, parent, false)) as TextView
                view.text = AllDemos[groupPosition].demos[childPosition].name
                return view
            }

            override fun getChildId(groupPosition: Int, childPosition: Int): Long {
                return (groupPosition.toLong() shl 32) or childPosition.toLong()
            }

            override fun getGroupCount(): Int {
                return AllDemos.size
            }
        })

        list.setOnChildClickListener { _, _, groupPosition, childPosition, _ ->
            val demo = AllDemos[groupPosition].demos[childPosition]
            startActivity(demo.makeIntent(this))
            return@setOnChildClickListener true
        }

        AllDemos.forEachIndexed { index, _ -> list.expandGroup(index) }
    }
}