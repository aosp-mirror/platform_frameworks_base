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
import android.view.ViewPropertyAnimator
import android.view.WindowInsets
import android.widget.FrameLayout
import androidx.annotation.StringRes
import com.android.keyguard.LockIconViewController
import com.android.systemui.R
import com.android.systemui.keyguard.ui.binder.KeyguardBottomAreaViewBinder
import com.android.systemui.keyguard.ui.binder.KeyguardBottomAreaViewBinder.bind
import com.android.systemui.keyguard.ui.viewmodel.KeyguardBottomAreaViewModel
import com.android.systemui.plugins.FalsingManager
import com.android.systemui.statusbar.VibratorHelper

/**
 * Renders the bottom area of the lock-screen. Concerned primarily with the quick affordance UI
 * elements. A secondary concern is the interaction of the quick affordance elements with the
 * indication area between them, though the indication area is primarily controlled elsewhere.
 */
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

    interface MessageDisplayer {
        fun display(@StringRes stringResourceId: Int)
    }

    private var ambientIndicationArea: View? = null
    private lateinit var binding: KeyguardBottomAreaViewBinder.Binding
    private var lockIconViewController: LockIconViewController? = null

    /** Initializes the view. */
    fun init(
        viewModel: KeyguardBottomAreaViewModel,
        falsingManager: FalsingManager? = null,
        lockIconViewController: LockIconViewController? = null,
        messageDisplayer: MessageDisplayer? = null,
        vibratorHelper: VibratorHelper? = null,
    ) {
        binding =
            bind(
                this,
                viewModel,
                falsingManager,
                vibratorHelper,
            ) {
                messageDisplayer?.display(it)
            }
        this.lockIconViewController = lockIconViewController
    }

    /**
     * Initializes this instance of [KeyguardBottomAreaView] based on the given instance of another
     * [KeyguardBottomAreaView]
     */
    fun initFrom(oldBottomArea: KeyguardBottomAreaView) {
        // if it exists, continue to use the original ambient indication container
        // instead of the newly inflated one
        ambientIndicationArea?.let { nonNullAmbientIndicationArea ->
            // remove old ambient indication from its parent
            val originalAmbientIndicationView =
                oldBottomArea.findViewById<View>(R.id.ambient_indication_container)
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

    override fun onFinishInflate() {
        super.onFinishInflate()
        ambientIndicationArea = findViewById(R.id.ambient_indication_container)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.onConfigurationChanged()
    }

    /** Returns a list of animators to use to animate the indication areas. */
    val indicationAreaAnimators: List<ViewPropertyAnimator>
        get() = binding.getIndicationAreaAnimators()

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
            if (binding.shouldConstrainToTopOfLockIcon()) {
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
