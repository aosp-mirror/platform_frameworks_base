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
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewStub
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.android.systemui.R
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.controls.controller.ControlInfo
import com.android.systemui.controls.controller.ControlsControllerImpl
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.CurrentUserTracker
import java.util.concurrent.Executor
import java.util.function.Consumer
import javax.inject.Inject

class ControlsFavoritingActivity @Inject constructor(
    @Main private val executor: Executor,
    private val controller: ControlsControllerImpl,
    broadcastDispatcher: BroadcastDispatcher
) : Activity() {

    companion object {
        private const val TAG = "ControlsFavoritingActivity"
        const val EXTRA_APP = "extra_app_label"
    }

    private lateinit var recyclerViewAll: RecyclerView
    private lateinit var adapterAll: ControlAdapter
    private lateinit var recyclerViewFavorites: RecyclerView
    private lateinit var adapterFavorites: ControlAdapter
    private lateinit var errorText: TextView
    private var component: ComponentName? = null

    private var currentModel: FavoriteModel? = null
    private var itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(
            /* dragDirs */ ItemTouchHelper.UP
                    or ItemTouchHelper.DOWN
                    or ItemTouchHelper.LEFT
                    or ItemTouchHelper.RIGHT,
            /* swipeDirs */0
    ) {
        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            return currentModel?.onMoveItem(
                    viewHolder.adapterPosition, target.adapterPosition) != null
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

        override fun isItemViewSwipeEnabled() = false
    }

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
        requireViewById<ViewStub>(R.id.stub).apply {
            layoutResource = R.layout.controls_management_favorites
            inflate()
        }

        val app = intent.getCharSequenceExtra(EXTRA_APP)
        component = intent.getParcelableExtra<ComponentName>(Intent.EXTRA_COMPONENT_NAME)
        errorText = requireViewById(R.id.error_message)

        setUpRecyclerViews()

        requireViewById<TextView>(R.id.title).text = app?.let { it }
                ?: resources.getText(R.string.controls_favorite_default_title)
        requireViewById<TextView>(R.id.subtitle).text =
                resources.getText(R.string.controls_favorite_subtitle)

        requireViewById<Button>(R.id.done).setOnClickListener {
            if (component == null) return@setOnClickListener
            val favoritesForStorage = currentModel?.favorites?.map {
                with(it.controlStatus.control) {
                    ControlInfo(component!!, controlId, title, deviceType)
                }
            }
            if (favoritesForStorage != null) {
                controller.replaceFavoritesForComponent(component!!, favoritesForStorage)
                finishAffinity()
            }
        }

        component?.let {
            controller.loadForComponent(it, Consumer { data ->
                val allControls = data.allControls
                val favoriteKeys = data.favoritesIds
                val error = data.errorOnLoad
                executor.execute {
                    val favoriteModel = FavoriteModel(
                        allControls,
                        favoriteKeys,
                        allAdapter = adapterAll,
                        favoritesAdapter = adapterFavorites)
                    adapterAll.changeFavoritesModel(favoriteModel)
                    adapterFavorites.changeFavoritesModel(favoriteModel)
                    currentModel = favoriteModel
                    errorText.visibility = if (error) View.VISIBLE else View.GONE
                }
            })
        }

        currentUserTracker.startTracking()
    }

    private fun setUpRecyclerViews() {
        val margin = resources.getDimensionPixelSize(R.dimen.controls_card_margin)
        val itemDecorator = MarginItemDecorator(margin, margin)
        val layoutInflater = LayoutInflater.from(applicationContext)

        adapterAll = ControlAdapter(layoutInflater)
        recyclerViewAll = requireViewById<RecyclerView>(R.id.listAll).apply {
            adapter = adapterAll
            layoutManager = GridLayoutManager(applicationContext, 2).apply {
                spanSizeLookup = adapterAll.spanSizeLookup
            }
            addItemDecoration(itemDecorator)
        }

        adapterFavorites = ControlAdapter(layoutInflater, true)
        recyclerViewFavorites = requireViewById<RecyclerView>(R.id.listFavorites).apply {
            layoutManager = GridLayoutManager(applicationContext, 2)
            adapter = adapterFavorites
            addItemDecoration(itemDecorator)
        }
        ItemTouchHelper(itemTouchHelperCallback).attachToRecyclerView(recyclerViewFavorites)
    }

    override fun onDestroy() {
        currentUserTracker.stopTracking()
        super.onDestroy()
    }
}