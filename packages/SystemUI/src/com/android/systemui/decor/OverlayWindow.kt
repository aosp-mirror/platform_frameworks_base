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
package com.android.systemui.decor

import android.annotation.IdRes
import android.content.Context
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import com.android.systemui.RegionInterceptingFrameLayout

class OverlayWindow(private val context: Context) {

    val rootView = RegionInterceptingFrameLayout(context) as ViewGroup
    private val viewProviderMap = mutableMapOf<Int, Pair<View, DecorProvider>>()

    val viewIds: List<Int>
        get() = viewProviderMap.keys.toList()

    fun addDecorProvider(
        decorProvider: DecorProvider,
        @Surface.Rotation rotation: Int
    ) {
        val view = decorProvider.inflateView(context, rootView, rotation)
        viewProviderMap[decorProvider.viewId] = Pair(view, decorProvider)
    }

    fun getView(@IdRes id: Int): View? {
        val pair = viewProviderMap[id]
        return pair?.first
    }

    fun removeView(@IdRes id: Int) {
        val view = getView(id)
        if (view != null) {
            rootView.removeView(view)
            viewProviderMap.remove(id)
        }
    }

    /**
     * Remove views which does not been found in expectExistViewIds
     */
    fun removeRedundantViews(expectExistViewIds: IntArray?) {
        viewIds.forEach {
            if (expectExistViewIds == null || !(expectExistViewIds.contains(it))) {
                removeView(it)
            }
        }
    }

    /**
     * Check that newProviders is the same list with viewProviderMap.
     */
    fun hasSameProviders(newProviders: List<DecorProvider>): Boolean {
        return (newProviders.size == viewProviderMap.size) &&
                newProviders.all { getView(it.viewId) != null }
    }

    /**
     * Apply new configuration info into views.
     * @param filterIds target view ids. Apply to all if null.
     * @param rotation current or new rotation direction.
     * @param displayUniqueId new displayUniqueId if any.
     */
    fun onReloadResAndMeasure(
        filterIds: Array<Int>? = null,
        reloadToken: Int,
        @Surface.Rotation rotation: Int,
        displayUniqueId: String? = null
    ) {
        filterIds?.forEach { id ->
            viewProviderMap[id]?.let {
                it.second.onReloadResAndMeasure(
                        view = it.first,
                        reloadToken = reloadToken,
                        displayUniqueId = displayUniqueId,
                        rotation = rotation)
            }
        } ?: run {
            viewProviderMap.values.forEach {
                it.second.onReloadResAndMeasure(
                        view = it.first,
                        reloadToken = reloadToken,
                        displayUniqueId = displayUniqueId,
                        rotation = rotation)
            }
        }
    }
}