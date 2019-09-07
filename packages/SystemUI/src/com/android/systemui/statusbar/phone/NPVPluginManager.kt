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

package com.android.systemui.statusbar.phone

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import com.android.systemui.plugins.NPVPlugin
import com.android.systemui.plugins.PluginListener
import com.android.systemui.qs.TouchAnimator
import com.android.systemui.shared.plugins.PluginManager

/**
 * Manages the NPVPlugin view and state
 *
 * Abstracts NPVPlugin from NPV and helps animate on expansion and respond to changes in Config.
 */
class NPVPluginManager(
    var parent: FrameLayout,
    val pluginManager: PluginManager
) : PluginListener<NPVPlugin> {

    private var plugin: NPVPlugin? = null
    private var animator = createAnimator()

    private fun createAnimator() = TouchAnimator.Builder()
            .addFloat(parent, "alpha", 1f, 0f)
            .addFloat(parent, "scaleY", 1f, 0f)
            .build()

    init {
        pluginManager.addPluginListener(NPVPlugin.ACTION, this, NPVPlugin::class.java, false)
        parent.pivotY = 0f
    }

    override fun onPluginConnected(plugin: NPVPlugin, pluginContext: Context) {
        parent.removeAllViews()
        plugin.attachToRoot(parent)
        this.plugin = plugin
        parent.visibility = View.VISIBLE
    }

    fun changeVisibility(visibility: Int) {
        parent.visibility = if (plugin != null) visibility else View.GONE
    }

    fun destroy() {
        plugin?.onDestroy()
        pluginManager.removePluginListener(this)
    }

    override fun onPluginDisconnected(plugin: NPVPlugin) {
        if (this.plugin == plugin) {
            this.plugin = null
            parent.removeAllViews()
            parent.visibility = View.GONE
        }
    }

    fun setListening(listening: Boolean) {
        plugin?.setListening(listening)
    }

    fun setExpansion(expansion: Float, headerTranslation: Float, heightDiff: Float) {
        parent.setTranslationY(expansion * heightDiff + headerTranslation)
        if (!expansion.isNaN()) animator.setPosition(expansion)
    }

    fun replaceFrameLayout(newParent: FrameLayout) {
        newParent.visibility = parent.visibility
        parent.removeAllViews()
        plugin?.attachToRoot(newParent)
        parent = newParent
        animator = createAnimator()
    }

    fun getHeight() = if (plugin != null) parent.height else 0
}
