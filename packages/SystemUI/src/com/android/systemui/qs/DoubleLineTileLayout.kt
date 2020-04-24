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
import com.android.internal.logging.UiEventLogger
import com.android.systemui.R
import com.android.systemui.qs.TileLayout.exactly

class DoubleLineTileLayout(
    context: Context,
    private val uiEventLogger: UiEventLogger
) : ViewGroup(context), QSPanel.QSTileLayout {

    companion object {
        private const val NUM_LINES = 2
    }

    protected val mRecords = ArrayList<QSPanel.TileRecord>()
    private var _listening = false
    private var smallTileSize = 0
    private val twoLineHeight
        get() = smallTileSize * NUM_LINES + cellMarginVertical * (NUM_LINES - 1)
    private var cellMarginHorizontal = 0
    private var cellMarginVertical = 0
    private var tilesToShow = 0

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
            cellMarginHorizontal = getDimensionPixelSize(R.dimen.qs_tile_margin_horizontal_two_line)
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
        if (listening) {
            for (i in 0 until numVisibleTiles) {
                val tile = mRecords[i].tile
                uiEventLogger.logWithInstanceId(
                        QSEvent.QQS_TILE_VISIBLE, 0, tile.metricsSpec, tile.instanceId)
            }
        }
    }

    override fun getNumVisibleTiles() = tilesToShow

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateResources()
        postInvalidate()
    }

    override fun onFinishInflate() {
        updateResources()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {

        mRecords.forEach {
            it.tileView.measure(exactly(smallTileSize), exactly(smallTileSize))
        }

        val height = twoLineHeight
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    private fun calculateMaxColumns(availableWidth: Int): Int {
        if (smallTileSize + cellMarginHorizontal == 0) {
            return 0
        } else {
            return (availableWidth - smallTileSize) / (smallTileSize + cellMarginHorizontal) + 1
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val availableWidth = r - l - paddingLeft - paddingRight
        val maxColumns = calculateMaxColumns(availableWidth)
        val actualColumns = Math.min(maxColumns, mRecords.size / NUM_LINES)
        if (actualColumns == 0) {
            // No tileSize or horizontal margin
            return
        }
        tilesToShow = actualColumns * NUM_LINES

        val interTileSpace = if (actualColumns <= 2) {
            // Extra "column" of padding to be distributed on each end
            (availableWidth - actualColumns * smallTileSize) / actualColumns
        } else {
            (availableWidth - actualColumns * smallTileSize) / (actualColumns - 1)
        }

        for (index in 0 until mRecords.size) {
            val tileView = mRecords[index].tileView
            if (index >= tilesToShow) {
                tileView.visibility = View.GONE
            } else {
                tileView.visibility = View.VISIBLE
                if (index > 0) tileView.updateAccessibilityOrder(mRecords[index - 1].tileView)
                val column = index % actualColumns
                val left = getLeftForColumn(column, interTileSpace, actualColumns <= 2)
                val top = if (index < actualColumns) 0 else getTopBottomRow()
                tileView.layout(left, top, left + smallTileSize, top + smallTileSize)
            }
        }
    }

    private fun getLeftForColumn(column: Int, interSpace: Int, sideMargin: Boolean): Int {
        return (if (sideMargin) interSpace / 2 else 0) + column * (smallTileSize + interSpace)
    }

    private fun getTopBottomRow() = smallTileSize + cellMarginVertical
}