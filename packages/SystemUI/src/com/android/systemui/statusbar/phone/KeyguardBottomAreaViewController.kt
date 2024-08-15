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

package com.android.systemui.statusbar.phone

import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.Flags.smartspaceRelocateToBottom
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.android.systemui.res.R
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import com.android.systemui.util.ViewController
import javax.inject.Inject

class KeyguardBottomAreaViewController
    @Inject constructor(
            view: KeyguardBottomAreaView,
            private val smartspaceController: LockscreenSmartspaceController,
            featureFlags: FeatureFlagsClassic
) : ViewController<KeyguardBottomAreaView> (view) {

    private var smartspaceView: View? = null

    init {
        view.setIsLockscreenLandscapeEnabled(
                featureFlags.isEnabled(Flags.LOCKSCREEN_ENABLE_LANDSCAPE))
    }

    override fun onViewAttached() {
        if (!smartspaceRelocateToBottom() || !smartspaceController.isEnabled) {
            return
        }

        val ambientIndicationArea = mView.findViewById<View>(R.id.ambient_indication_container)
        ambientIndicationArea?.visibility = View.GONE

        addSmartspaceView()
    }

    override fun onViewDetached() {
    }

    fun getView(): KeyguardBottomAreaView {
        // TODO: remove this method.
        return mView
    }

    private fun addSmartspaceView() {
        if (!smartspaceRelocateToBottom()) {
            return
        }

        val smartspaceContainer = mView.findViewById<View>(R.id.smartspace_container)
        smartspaceContainer!!.visibility = View.VISIBLE

        smartspaceView = smartspaceController.buildAndConnectView(smartspaceContainer as ViewGroup)
        val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        (smartspaceContainer as ViewGroup).addView(smartspaceView, 0, lp)
        val startPadding = context.resources.getDimensionPixelSize(
                R.dimen.below_clock_padding_start)
        val endPadding = context.resources.getDimensionPixelSize(
                R.dimen.below_clock_padding_end)
        smartspaceView?.setPaddingRelative(startPadding, 0, endPadding, 0)
//        mKeyguardUnlockAnimationController.lockscreenSmartspace = smartspaceView
    }
}
