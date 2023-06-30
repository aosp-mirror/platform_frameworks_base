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
 *
 */

package com.android.systemui.keyguard.ui.view.layout

import android.content.res.Configuration
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.android.systemui.keyguard.ui.view.layout.DefaultLockscreenLayout.Companion.DEFAULT
import com.android.systemui.statusbar.policy.ConfigurationController
import javax.inject.Inject

/**
 * Manages layout changes for the lockscreen.
 *
 * To add a layout, add an entry to the map with a unique id and call #transitionToLayout(string).
 */
@SysUISingleton
class KeyguardLayoutManager
@Inject
constructor(
    configurationController: ConfigurationController,
    layouts: Set<@JvmSuppressWildcards LockscreenLayout>,
    private val keyguardRootView: KeyguardRootView,
) {
    internal val layoutIdMap: Map<String, LockscreenLayout> = layouts.associateBy { it.id }
    private var layout: LockscreenLayout? = layoutIdMap[DEFAULT]

    init {
        configurationController.addCallback(
            object : ConfigurationController.ConfigurationListener {
                override fun onConfigChanged(newConfig: Configuration?) {
                    layoutViews()
                }
            }
        )
    }

    /**
     * Transitions to a layout.
     *
     * @param layoutId
     * @return whether the transition has succeeded.
     */
    fun transitionToLayout(layoutId: String): Boolean {
        layout = layoutIdMap[layoutId] ?: return false
        layoutViews()
        return true
    }

    fun layoutViews() {
        layout?.layoutViews(keyguardRootView)
    }

    companion object {
        const val TAG = "KeyguardLayoutManager"
    }
}

interface LockscreenLayout {
    val id: String

    fun layoutViews(rootView: KeyguardRootView) {
        // Clear constraints.
        ConstraintSet()
            .apply {
                clone(rootView)
                knownIds.forEach { getConstraint(it).layout.copyFrom(ConstraintSet.Layout()) }
            }
            .applyTo(rootView)
        layoutIndicationArea(rootView)
        layoutLockIcon(rootView)
    }
    fun layoutIndicationArea(rootView: KeyguardRootView)
    fun layoutLockIcon(rootView: KeyguardRootView)
}
