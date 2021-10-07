/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.egg.widget

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.widget.FrameLayout

/**
 * Activity to show off the current dynamic system theme in all its glory.
 */
class PaintChipsActivity : Activity() {
    private lateinit var layout: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.navigationBarColor = 0
        window.statusBarColor = 0
        actionBar?.hide()

        layout = FrameLayout(this)
        layout.setPadding(dp2px(8f), dp2px(8f), dp2px(8f), dp2px(8f))
        rebuildGrid()

        setContentView(layout)
    }

    fun dp2px(dp: Float): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    override fun onResume() {
        super.onResume()

        rebuildGrid()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

        rebuildGrid()
    }

    private fun rebuildGrid() {
        layout.removeAllViews()
        val grid = buildFullWidget(this, ClickBehavior.SHARE)
        val asView = grid.apply(this, layout)
        layout.addView(asView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT))
    }
}
