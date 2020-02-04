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

package com.android.systemui.controls.management

import android.app.Activity
import android.content.ComponentName
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.android.systemui.R
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.controls.controller.ControlInfo
import com.android.systemui.controls.controller.ControlsControllerImpl
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.CurrentUserTracker
import java.util.concurrent.Executor
import javax.inject.Inject

class ControlsFavoritingActivity @Inject constructor(
    @Main private val executor: Executor,
    private val controller: ControlsControllerImpl,
    broadcastDispatcher: BroadcastDispatcher
) : Activity() {

    companion object {
        private const val TAG = "ControlsFavoritingActivity"
        const val EXTRA_APP = "extra_app_label"
        const val EXTRA_COMPONENT = "extra_component"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ControlAdapter
    private var component: ComponentName? = null

    private val currentUserTracker = object : CurrentUserTracker(broadcastDispatcher) {
        private val startingUser = controller.currentUserId

        override fun onUserSwitched(newUserId: Int) {
            if (newUserId != startingUser) {
                stopTracking()
                finish()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.controls_management)

        val app = intent.getCharSequenceExtra(EXTRA_APP)
        component = intent.getParcelableExtra<ComponentName>(EXTRA_COMPONENT)

        // If we have no component name, there's not much we can do.
        val callback = component?.let {
            { infoBuilder: ControlInfo.Builder, status: Boolean ->
                infoBuilder.componentName = it
                controller.changeFavoriteStatus(infoBuilder.build(), status)
            }
        } ?: { _, _ -> Unit }

        recyclerView = requireViewById(R.id.list)
        adapter = ControlAdapter(LayoutInflater.from(applicationContext), callback)
        recyclerView.adapter = adapter
        recyclerView.layoutManager = GridLayoutManager(applicationContext, 2)
        val margin = resources.getDimensionPixelSize(R.dimen.controls_card_margin)
        recyclerView.addItemDecoration(MarginItemDecorator(margin, margin))

        requireViewById<TextView>(R.id.title).text = app?.let { it }
                ?: resources.getText(R.string.controls_favorite_default_title)
        requireViewById<TextView>(R.id.subtitle).text =
                resources.getText(R.string.controls_favorite_subtitle)

        currentUserTracker.startTracking()
    }

    override fun onResume() {
        super.onResume()
        component?.let {
            controller.loadForComponent(it) {
                executor.execute {
                    adapter.setItems(it)
                }
            }
        }
    }
}