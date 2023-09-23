/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License
 */
package com.android.systemui.statusbar.phone

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.annotation.StringRes
import com.android.keyguard.LockIconViewController
import com.android.systemui.res.R
import com.android.systemui.keyguard.ui.binder.KeyguardBottomAreaViewBinder
import com.android.systemui.keyguard.ui.binder.KeyguardBottomAreaViewBinder.bind
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBottomAreaViewModel
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.VibratorHelper

/**
 * Renders the bottom area of the lock-screen. Concerned primarily with the quick affordance UI
 * elements. A secondary concern is the interaction of the quick affordance elements with the
 * indication area between them, though the indication area is primarily controlled elsewhere.
 */
@Deprecated("Deprecated as part of b/278057014")
class KeyguardBottomAreaView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) :
    FrameLayout(
        context,
        attrs,
        defStyleAttr,
        defStyleRes,
    ) {

    @Deprecated("Deprecated as part of b/278057014")
    interface MessageDisplayer {
        fun display(@StringRes stringResourceId: Int)
    }

    private var ambientIndicationArea: View? = null
    private var keyguardIndicationArea: View? = null
    private var binding: KeyguardBottomAreaViewBinder.Binding? = null
    private var lockIconViewController: LockIconViewController? = null
    private var isLockscreenLandscapeEnabled: Boolean = false

    /** Initializes the view. */
    @Deprecated("Deprecated as part of b/278057014")
    fun init(
        viewModel: KeyguardBottomAreaViewModel,
        falsingManager: FalsingManager? = null,
        lockIconViewController: LockIconViewController? = null,
        messageDisplayer: MessageDisplayer? = null,
        vibratorHelper: VibratorHelper? = null,
        activityStarter: ActivityStarter? = null,
    ) {
        binding?.destroy()
        binding =
            bind(
                this,
                viewModel,
                falsingManager,
                vibratorHelper,
                activityStarter,
            ) {
                messageDisplayer?.display(it)
            }
        this.lockIconViewController = lockIconViewController
    }

    /**
     * Initializes this instance of [KeyguardBottomAreaView] based on the given instance of another
     * [KeyguardBottomAreaView]
     */
    @Deprecated("Deprecated as part of b/278057014")
    fun initFrom(oldBottomArea: KeyguardBottomAreaView) {
        // if it exists, continue to use the original ambient indication container
        // instead of the newly inflated one
        ambientIndicationArea?.let { nonNullAmbientIndicationArea ->
            // remove old ambient indication from its parent
            val originalAmbientIndicationView =
                oldBottomArea.requireViewById<View>(R.id.ambient_indication_container)
            (originalAmbientIndicationView.parent as ViewGroup).removeView(
                originalAmbientIndicationView
            )

            // remove current ambient indication from its parent (discard)
            val ambientIndicationParent = nonNullAmbientIndicationArea.parent as ViewGroup
            val ambientIndicationIndex =
                ambientIndicationParent.indexOfChild(nonNullAmbientIndicationArea)
            ambientIndicationParent.removeView(nonNullAmbientIndicationArea)

            // add the old ambient indication to this view
            ambientIndicationParent.addView(originalAmbientIndicationView, ambientIndicationIndex)
            ambientIndicationArea = originalAmbientIndicationView
        }
    }

    fun setIsLockscreenLandscapeEnabled(isLockscreenLandscapeEnabled: Boolean) {
        this.isLockscreenLandscapeEnabled = isLockscreenLandscapeEnabled
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        ambientIndicationArea = findViewById(R.id.ambient_indication_container)
        keyguardIndicationArea = findViewById(R.id.keyguard_indication_area)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding?.onConfigurationChanged()

        if (isLockscreenLandscapeEnabled) {
            updateIndicationAreaBottomMargin()
        }
    }

    private fun updateIndicationAreaBottomMargin() {
        keyguardIndicationArea?.let {
            val params = it.layoutParams as FrameLayout.LayoutParams
            params.bottomMargin =
                resources.getDimensionPixelSize(R.dimen.keyguard_indication_margin_bottom)
            it.layoutParams = params
        }
    }

    override fun hasOverlappingRendering(): Boolean {
        return false
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bottom = insets.displayCutout?.safeInsetBottom ?: 0
        if (isPaddingRelative) {
            setPaddingRelative(paddingStart, paddingTop, paddingEnd, bottom)
        } else {
            setPadding(paddingLeft, paddingTop, paddingRight, bottom)
        }
        return insets
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        findViewById<View>(R.id.ambient_indication_container)?.let {
            val (ambientLeft, ambientTop) = it.locationOnScreen
            if (binding?.shouldConstrainToTopOfLockIcon() == true) {
                // make top of ambient indication view the bottom of the lock icon
                it.layout(
                    ambientLeft,
                    lockIconViewController?.bottom?.toInt() ?: 0,
                    right - ambientLeft,
                    ambientTop + it.measuredHeight
                )
            } else {
                // make bottom of ambient indication view the top of the lock icon
                val lockLocationTop = lockIconViewController?.top ?: 0
                it.layout(
                    ambientLeft,
                    lockLocationTop.toInt() - it.measuredHeight,
                    right - ambientLeft,
                    lockLocationTop.toInt()
                )
            }
        }
    }
}
