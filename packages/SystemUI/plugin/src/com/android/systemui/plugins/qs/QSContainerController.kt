package com.android.systemui.plugins.qs

interface QSContainerController {
    fun setCustomizerAnimating(animating: Boolean)

    fun setCustomizerShowing(showing: Boolean)

    fun setDetailShowing(showing: Boolean)
}