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
 */

package com.android.systemui.qs.ui.adapter

import android.content.Context
import android.view.View
import com.android.systemui.settings.brightness.MirrorController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull

class FakeQSSceneAdapter(
    private val inflateDelegate: suspend (Context) -> View,
    override val qqsHeight: Int = 0,
    override val qsHeight: Int = 0,
) : QSSceneAdapter {
    private val _customizerState = MutableStateFlow<CustomizerState>(CustomizerState.Hidden)

    private val _customizerShowing = MutableStateFlow(false)
    override val isCustomizerShowing = _customizerShowing.asStateFlow()

    private val _customizing = MutableStateFlow(false)
    override val isCustomizing = _customizing.asStateFlow()

    private val _animationDuration = MutableStateFlow(0)
    override val customizerAnimationDuration = _animationDuration.asStateFlow()

    private val _view = MutableStateFlow<View?>(null)
    override val qsView: StateFlow<View?> = _view.asStateFlow()

    private val _state = MutableStateFlow<QSSceneAdapter.State?>(null)
    val state = _state.filterNotNull()

    private val _navBarPadding = MutableStateFlow<Int>(0)
    val navBarPadding = _navBarPadding.asStateFlow()

    var brightnessMirrorController: MirrorController? = null
        private set

    override var isQsFullyCollapsed: Boolean = true

    override suspend fun inflate(context: Context) {
        _view.value = inflateDelegate(context)
    }

    override fun setState(state: QSSceneAdapter.State) {
        if (_view.value != null) {
            _state.value = state
        }
    }

    override fun applyLatestExpansionAndSquishiness() {}

    fun setCustomizing(value: Boolean) {
        updateCustomizerFlows(if (value) CustomizerState.Showing else CustomizerState.Hidden)
    }

    override suspend fun applyBottomNavBarPadding(padding: Int) {
        _navBarPadding.value = padding
    }

    override fun requestCloseCustomizer() {
        updateCustomizerFlows(CustomizerState.Hidden)
    }

    override fun setBrightnessMirrorController(mirrorController: MirrorController?) {
        brightnessMirrorController = mirrorController
    }

    private fun updateCustomizerFlows(customizerState: CustomizerState) {
        _customizerState.value = customizerState
        _customizing.value = customizerState.isCustomizing
        _customizerShowing.value = customizerState.isShowing
        _animationDuration.value =
            (customizerState as? CustomizerState.Animating)?.animationDuration?.toInt() ?: 0
    }
}
