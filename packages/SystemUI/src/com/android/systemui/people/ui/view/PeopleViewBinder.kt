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

package com.android.systemui.people.ui.view

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Lifecycle.State.CREATED
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.R
import com.android.systemui.people.PeopleSpaceTileView
import com.android.systemui.people.ui.viewmodel.PeopleTileViewModel
import com.android.systemui.people.ui.viewmodel.PeopleViewModel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** A ViewBinder for [PeopleViewModel]. */
object PeopleViewBinder {
    private const val TAG = "PeopleViewBinder"

    /**
     * The [ViewOutlineProvider] used to clip the corner radius of the recent and priority lists.
     */
    private val ViewOutlineProvider =
        object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(
                    0,
                    0,
                    view.width,
                    view.height,
                    view.context.resources.getDimension(R.dimen.people_space_widget_radius),
                )
            }
        }

    /** Create a [View] that can later be [bound][bind] to a [PeopleViewModel]. */
    @JvmStatic
    fun create(context: Context): ViewGroup {
        return LayoutInflater.from(context)
            .inflate(R.layout.people_space_activity, /* root= */ null) as ViewGroup
    }

    /** Bind [view] to [viewModel]. */
    @JvmStatic
    fun bind(
        view: ViewGroup,
        viewModel: PeopleViewModel,
        lifecycleOwner: LifecycleOwner,
        onResult: (PeopleViewModel.Result) -> Unit,
    ) {
        // Call [onResult] as soon as a result is available.
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(CREATED) {
                viewModel.result.collect { result ->
                    if (result != null) {
                        viewModel.clearResult()
                        onResult(result)
                    }
                }
            }
        }

        // Start collecting the UI data once the Activity is STARTED.
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                        viewModel.priorityTiles,
                        viewModel.recentTiles,
                    ) { priority, recent ->
                        priority to recent
                    }
                    .collect { (priorityTiles, recentTiles) ->
                        if (priorityTiles.isNotEmpty() || recentTiles.isNotEmpty()) {
                            setConversationsContent(
                                view,
                                priorityTiles,
                                recentTiles,
                                viewModel::onTileClicked,
                            )
                        } else {
                            setNoConversationsContent(view, viewModel::onUserJourneyCancelled)
                        }
                    }
            }
        }

        // Make sure to refresh the tiles/conversations when the Activity is resumed, so that it
        // updates them when going back to the Activity after leaving it.
        lifecycleOwner.lifecycleScope.launch {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.onTileRefreshRequested()
            }
        }
    }

    private fun setNoConversationsContent(view: ViewGroup, onGotItClicked: () -> Unit) {
        // This should never happen.
        if (view.childCount > 1) {
            error("view has ${view.childCount} children, it should have maximum 1")
        }

        // The static content for no conversations is already shown.
        if (view.findViewById<View>(R.id.top_level_no_conversations) != null) {
            return
        }

        // If we were showing the content with conversations earlier, remove it.
        if (view.childCount == 1) {
            view.removeViewAt(0)
        }

        val context = view.context
        val noConversationsView =
            LayoutInflater.from(context)
                .inflate(R.layout.people_space_activity_no_conversations, /* root= */ view)

        noConversationsView.requireViewById<View>(R.id.got_it_button).setOnClickListener {
            onGotItClicked()
        }

        // The Tile preview has colorBackground as its background. Change it so it's different than
        // the activity's background.
        val item = noConversationsView.requireViewById<LinearLayout>(android.R.id.background)
        val shape = item.background as GradientDrawable
        val ta =
            context.theme.obtainStyledAttributes(
                intArrayOf(com.android.internal.R.attr.colorSurface)
            )
        shape.setColor(ta.getColor(0, Color.WHITE))
        ta.recycle()
    }

    private fun setConversationsContent(
        view: ViewGroup,
        priorityTiles: List<PeopleTileViewModel>,
        recentTiles: List<PeopleTileViewModel>,
        onTileClicked: (PeopleTileViewModel) -> Unit,
    ) {
        // This should never happen.
        if (view.childCount > 1) {
            error("view has ${view.childCount} children, it should have maximum 1")
        }

        // Inflate the content with conversations, if it's not already.
        if (view.findViewById<View>(R.id.top_level_with_conversations) == null) {
            // If we were showing the content without conversations earlier, remove it.
            if (view.childCount == 1) {
                view.removeViewAt(0)
            }

            LayoutInflater.from(view.context)
                .inflate(R.layout.people_space_activity_with_conversations, /* root= */ view)
        }

        // TODO(b/193782241): Replace the NestedScrollView + 2x LinearLayout from this layout into a
        // single RecyclerView once this screen is tested by screenshot tests. Introduce a
        // PeopleSpaceTileViewBinder that will properly create and bind the View associated to a
        // PeopleSpaceTileViewModel (and remove the PeopleSpaceTileView class).
        val conversationsView = view.requireViewById<View>(R.id.top_level_with_conversations)
        setTileViews(
            conversationsView,
            R.id.priority,
            R.id.priority_tiles,
            priorityTiles,
            onTileClicked,
        )

        setTileViews(
            conversationsView,
            R.id.recent,
            R.id.recent_tiles,
            recentTiles,
            onTileClicked,
        )
    }

    /** Sets a [PeopleSpaceTileView]s for each conversation. */
    private fun setTileViews(
        root: View,
        tilesListId: Int,
        tilesId: Int,
        tiles: List<PeopleTileViewModel>,
        onTileClicked: (PeopleTileViewModel) -> Unit,
    ) {
        // Remove any previously added tile.
        // TODO(b/193782241): Once this list is a big RecyclerView, set the current list and use
        // DiffUtil to do as less addView/removeView as possible.
        val layout = root.requireViewById<ViewGroup>(tilesId)
        layout.removeAllViews()
        layout.outlineProvider = ViewOutlineProvider

        val tilesListView = root.requireViewById<LinearLayout>(tilesListId)
        if (tiles.isEmpty()) {
            tilesListView.visibility = View.GONE
            return
        }
        tilesListView.visibility = View.VISIBLE

        // Add each tile.
        tiles.forEachIndexed { i, tile ->
            val tileView =
                PeopleSpaceTileView(root.context, layout, tile.key.shortcutId, i == tiles.size - 1)
            bindTileView(tileView, tile, onTileClicked)
        }
    }

    /** Sets [tileView] with the data in [conversation]. */
    private fun bindTileView(
        tileView: PeopleSpaceTileView,
        tile: PeopleTileViewModel,
        onTileClicked: (PeopleTileViewModel) -> Unit,
    ) {
        try {
            tileView.setName(tile.username)
            tileView.setPersonIcon(tile.icon)
            tileView.setOnClickListener { onTileClicked(tile) }
        } catch (e: Exception) {
            Log.e(TAG, "Couldn't retrieve shortcut information", e)
        }
    }
}
