/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.qs

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import com.android.systemui.R
import com.android.systemui.qs.TileLayout.exactly

class DoubleLineTileLayout(context: Context) : ViewGroup(context), QSPanel.QSTileLayout {

    protected val mRecords = ArrayList<QSPanel.TileRecord>()
    private var _listening = false
    private var smallTileSize = 0
    private val twoLineHeight
        get() = smallTileSize * 2 + cellMarginVertical
    private var cellMarginHorizontal = 0
    private var cellMarginVertical = 0

    init {
        isFocusableInTouchMode = true
        clipChildren = false
        clipToPadding = false

        updateResources()
    }

    override fun addTile(tile: QSPanel.TileRecord) {
        mRecords.add(tile)
        tile.tile.setListening(this, _listening)
        addTileView(tile)
    }

    protected fun addTileView(tile: QSPanel.TileRecord) {
        addView(tile.tileView)
    }

    override fun removeTile(tile: QSPanel.TileRecord) {
        mRecords.remove(tile)
        tile.tile.setListening(this, false)
        removeView(tile.tileView)
    }

    override fun removeAllViews() {
        mRecords.forEach { it.tile.setListening(this, false) }
        mRecords.clear()
        super.removeAllViews()
    }

    override fun getOffsetTop(tile: QSPanel.TileRecord?) = top

    override fun updateResources(): Boolean {
        with(mContext.resources) {
            smallTileSize = getDimensionPixelSize(R.dimen.qs_quick_tile_size)
            cellMarginHorizontal = getDimensionPixelSize(R.dimen.qs_tile_margin_horizontal) / 2
            cellMarginVertical = getDimensionPixelSize(R.dimen.new_qs_vertical_margin)
        }
        requestLayout()
        return false
    }

    override fun setListening(listening: Boolean) {
        if (_listening == listening) return
        _listening = listening
        for (record in mRecords) {
            record.tile.setListening(this, listening)
        }
    }

    override fun getNumVisibleTiles() = mRecords.size

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateResources()
    }

    override fun onFinishInflate() {
        updateResources()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var previousView: View = this
        var tiles = 0

        mRecords.forEach {
            val tileView = it.tileView
            if (tileView.visibility != View.GONE) {
                tileView.updateAccessibilityOrder(previousView)
                previousView = tileView
                tiles++
                tileView.measure(exactly(smallTileSize), exactly(smallTileSize))
            }
        }

        val height = twoLineHeight
        val columns = tiles / 2
        val width = paddingStart + paddingEnd +
                columns * smallTileSize +
                (columns - 1) * cellMarginHorizontal
        setMeasuredDimension(width, height)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val tiles = mRecords.filter { it.tileView.visibility != View.GONE }
        tiles.forEachIndexed {
            index, tile ->
            val column = index % (tiles.size / 2)
            val left = getLeftForColumn(column)
            val top = if (index < tiles.size / 2) 0 else getTopBottomRow()
            tile.tileView.layout(left, top, left + smallTileSize, top + smallTileSize)
        }
    }

    private fun getLeftForColumn(column: Int) = column * (smallTileSize + cellMarginHorizontal)

    private fun getTopBottomRow() = smallTileSize + cellMarginVertical
}