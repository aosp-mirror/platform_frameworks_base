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

package com.android.systemui.statusbar.pipeline.shared.ui.view

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.Gravity
import com.android.systemui.plugins.DarkIconDispatcher
import com.android.systemui.res.R
import com.android.systemui.statusbar.BaseStatusBarFrameLayout
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.StatusBarIconView.STATE_DOT
import com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN
import com.android.systemui.statusbar.pipeline.shared.ui.binder.ModernStatusBarViewBinding

/**
 * A new and more modern implementation of [BaseStatusBarFrameLayout] that gets updated by view
 * binders communicating via [ModernStatusBarViewBinding].
 */
open class ModernStatusBarView(context: Context, attrs: AttributeSet?) :
    BaseStatusBarFrameLayout(context, attrs) {

    private lateinit var slot: String
    internal lateinit var binding: ModernStatusBarViewBinding

    @StatusBarIconView.VisibleState
    private var iconVisibleState: Int = STATE_HIDDEN
        set(value) {
            if (field == value) {
                return
            }
            field = value
            binding.onVisibilityStateChanged(value)
        }

    override fun getSlot() = slot

    override fun onDarkChanged(areas: ArrayList<Rect>?, darkIntensity: Float, tint: Int) {
        // nop
    }

    override fun onDarkChangedWithContrast(areas: ArrayList<Rect>, tint: Int, contrastTint: Int) {
        val newTint = DarkIconDispatcher.getTint(areas, this, tint)
        val contrast = DarkIconDispatcher.getInverseTint(areas, this, contrastTint)

        binding.onIconTintChanged(newTint, contrast)
        binding.onDecorTintChanged(newTint)
    }

    override fun setStaticDrawableColor(color: Int) {
        // nop
    }

    override fun setStaticDrawableColor(color: Int, foregroundColor: Int) {
        binding.onIconTintChanged(color, foregroundColor)
    }

    override fun setDecorColor(color: Int) {
        binding.onDecorTintChanged(color)
    }

    override fun setVisibleState(@StatusBarIconView.VisibleState state: Int, animate: Boolean) {
        iconVisibleState = state
    }

    @StatusBarIconView.VisibleState
    override fun getVisibleState(): Int {
        return iconVisibleState
    }

    override fun isIconVisible(): Boolean {
        return binding.getShouldIconBeVisible()
    }

    /** See [StatusBarIconView.getDrawingRect]. */
    override fun getDrawingRect(outRect: Rect) {
        super.getDrawingRect(outRect)
        val translationX = translationX.toInt()
        val translationY = translationY.toInt()
        outRect.left += translationX
        outRect.right += translationX
        outRect.top += translationY
        outRect.bottom += translationY
    }

    /**
     * Initializes this view.
     *
     * Creates a dot view, and uses [bindingCreator] to get and set the binding.
     */
    fun initView(slot: String, bindingCreator: () -> ModernStatusBarViewBinding) {
        // The dot view requires [slot] to be set, and the [binding] may require an instantiated dot
        // view. So, this is the required order.
        this.slot = slot
        initDotView()
        this.binding = bindingCreator.invoke()
    }

    /** Creates a [StatusBarIconView] that is always in DOT mode and adds it to this view. */
    private fun initDotView() {
        // TODO(b/238425913): Could we just have this dot view be part of the layout with a dot
        //  drawable so we don't need to inflate it manually? Would that not work with animations?
        val dotView =
            StatusBarIconView(mContext, slot, null).also {
                it.id = R.id.status_bar_dot
                // Hard-code this view to always be in the DOT state so that whenever it's visible
                // it will show a dot
                it.visibleState = STATE_DOT
            }

        val width = mContext.resources.getDimensionPixelSize(R.dimen.status_bar_icon_size_sp)
        val lp = LayoutParams(width, width)
        lp.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        addView(dotView, lp)
    }
}
