package com.android.systemui.unfold

import com.android.systemui.unfold.UnfoldTransitionProgressProvider.TransitionProgressListener

class TestUnfoldTransitionProvider : UnfoldTransitionProgressProvider, TransitionProgressListener {

    private val listeners = arrayListOf<TransitionProgressListener>()

    override fun destroy() {
        listeners.clear()
    }

    override fun addCallback(listener: TransitionProgressListener) {
        listeners.add(listener)
    }

    override fun removeCallback(listener: TransitionProgressListener) {
        listeners.remove(listener)
    }

    override fun onTransitionStarted() {
        listeners.forEach { it.onTransitionStarted() }
    }

    override fun onTransitionFinished() {
        listeners.forEach { it.onTransitionFinished() }
    }

    override fun onTransitionProgress(progress: Float) {
        listeners.forEach { it.onTransitionProgress(progress) }
    }
}
