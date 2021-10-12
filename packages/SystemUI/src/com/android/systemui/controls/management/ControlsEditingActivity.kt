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

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.Button
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.android.systemui.R
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.controls.CustomIconCache
import com.android.systemui.controls.controller.ControlsControllerImpl
import com.android.systemui.controls.controller.StructureInfo
import com.android.systemui.controls.ui.ControlsActivity
import com.android.systemui.controls.ui.ControlsUiController
import com.android.systemui.settings.CurrentUserTracker
import com.android.systemui.util.LifecycleActivity
import javax.inject.Inject

/**
 * Activity for rearranging and removing controls for a given structure
 */
class ControlsEditingActivity @Inject constructor(
    private val controller: ControlsControllerImpl,
    private val broadcastDispatcher: BroadcastDispatcher,
    private val customIconCache: CustomIconCache,
    private val uiController: ControlsUiController
) : LifecycleActivity() {

    companion object {
        private const val TAG = "ControlsEditingActivity"
        private const val EXTRA_STRUCTURE = ControlsFavoritingActivity.EXTRA_STRUCTURE
        private val SUBTITLE_ID = R.string.controls_favorite_rearrange
        private val EMPTY_TEXT_ID = R.string.controls_favorite_removed
    }

    private lateinit var component: ComponentName
    private lateinit var structure: CharSequence
    private lateinit var model: FavoritesModel
    private lateinit var subtitle: TextView
    private lateinit var saveButton: View

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

        intent.getParcelableExtra<ComponentName>(Intent.EXTRA_COMPONENT_NAME)?.let {
            component = it
        } ?: run(this::finish)

        intent.getCharSequenceExtra(EXTRA_STRUCTURE)?.let {
            structure = it
        } ?: run(this::finish)

        bindViews()

        bindButtons()
    }

    override fun onStart() {
        super.onStart()
        setUpList()

        currentUserTracker.startTracking()
    }

    override fun onStop() {
        super.onStop()
        currentUserTracker.stopTracking()
    }

    override fun onBackPressed() {
        animateExitAndFinish()
    }

    private fun animateExitAndFinish() {
        val rootView = requireViewById<ViewGroup>(R.id.controls_management_root)
        ControlsAnimations.exitAnimation(
                rootView,
                object : Runnable {
                    override fun run() {
                        finish()
                    }
                }
        ).start()
    }

    private fun bindViews() {
        setContentView(R.layout.controls_management)

        getLifecycle().addObserver(
            ControlsAnimations.observerForAnimations(
                requireViewById<ViewGroup>(R.id.controls_management_root),
                window,
                intent
            )
        )

        requireViewById<ViewStub>(R.id.stub).apply {
            layoutResource = R.layout.controls_management_editing
            inflate()
        }
        requireViewById<TextView>(R.id.title).text = structure
        setTitle(structure)
        subtitle = requireViewById<TextView>(R.id.subtitle).apply {
            setText(SUBTITLE_ID)
        }
    }

    private fun bindButtons() {
        saveButton = requireViewById<Button>(R.id.done).apply {
            isEnabled = false
            setText(R.string.save)
            setOnClickListener {
                saveFavorites()
                startActivity(
                    Intent(applicationContext, ControlsActivity::class.java),
                    ActivityOptions
                        .makeSceneTransitionAnimation(this@ControlsEditingActivity).toBundle()
                )
                animateExitAndFinish()
            }
        }
    }

    private fun saveFavorites() {
        controller.replaceFavoritesForStructure(
                StructureInfo(component, structure, model.favorites))
    }

    private val favoritesModelCallback = object : FavoritesModel.FavoritesModelCallback {
        override fun onNoneChanged(showNoFavorites: Boolean) {
            if (showNoFavorites) {
                subtitle.setText(EMPTY_TEXT_ID)
            } else {
                subtitle.setText(SUBTITLE_ID)
            }
        }

        override fun onFirstChange() {
            saveButton.isEnabled = true
        }
    }

    private fun setUpList() {
        val controls = controller.getFavoritesForStructure(component, structure)
        model = FavoritesModel(customIconCache, component, controls, favoritesModelCallback)
        val elevation = resources.getFloat(R.dimen.control_card_elevation)
        val recyclerView = requireViewById<RecyclerView>(R.id.list)
        recyclerView.alpha = 0.0f
        val adapter = ControlAdapter(elevation).apply {
            registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                var hasAnimated = false
                override fun onChanged() {
                    if (!hasAnimated) {
                        hasAnimated = true
                        ControlsAnimations.enterAnimation(recyclerView).start()
                    }
                }
            })
        }

        val margin = resources
                .getDimensionPixelSize(R.dimen.controls_card_margin)
        val itemDecorator = MarginItemDecorator(margin, margin)

        recyclerView.apply {
            this.adapter = adapter
            layoutManager = object : GridLayoutManager(recyclerView.context, 2) {

                // This will remove from the announcement the row corresponding to the divider,
                // as it's not something that should be announced.
                override fun getRowCountForAccessibility(
                    recycler: RecyclerView.Recycler,
                    state: RecyclerView.State
                ): Int {
                    val initial = super.getRowCountForAccessibility(recycler, state)
                    return if (initial > 0) initial - 1 else initial
                }
            }.apply {
                spanSizeLookup = adapter.spanSizeLookup
            }
            addItemDecoration(itemDecorator)
        }
        adapter.changeModel(model)
        model.attachAdapter(adapter)
        ItemTouchHelper(model.itemTouchHelperCallback).attachToRecyclerView(recyclerView)
    }

    override fun onDestroy() {
        currentUserTracker.stopTracking()
        super.onDestroy()
    }
}
