/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.statusbar.events

import android.view.View

class FakePrivacyDotViewController : PrivacyDotViewController {

    var topLeft: View? = null
        private set

    var topRight: View? = null
        private set

    var bottomLeft: View? = null
        private set

    var bottomRight: View? = null
        private set

    var isInitialized = false
        private set

    override fun stop() {}

    override var currentViewState: ViewState = ViewState()

    override var showingListener: PrivacyDotViewController.ShowingListener? = null

    override fun setNewRotation(rot: Int) {}

    override fun hideDotView(dot: View, animate: Boolean) {}

    override fun showDotView(dot: View, animate: Boolean) {}

    override fun updateRotations(rotation: Int, paddingTop: Int) {}

    override fun setCornerSizes(state: ViewState) {}

    override fun initialize(topLeft: View, topRight: View, bottomLeft: View, bottomRight: View) {
        this.topLeft = topLeft
        this.topRight = topRight
        this.bottomLeft = bottomLeft
        this.bottomRight = bottomRight
        isInitialized = true
    }

    override fun updateDotView(state: ViewState) {}
}
