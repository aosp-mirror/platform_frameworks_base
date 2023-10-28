package com.android.systemui.statusbar

import android.view.View
import com.android.systemui.plugins.statusbar.StatusBarStateController

class FakeStatusBarStateController : SysuiStatusBarStateController {
    @JvmField var state = StatusBarState.SHADE

    @JvmField var upcomingState = StatusBarState.SHADE

    @JvmField var lastState = StatusBarState.SHADE

    @JvmField var dozing = false

    @JvmField var expanded = false

    @JvmField var pulsing = false

    @JvmField var dreaming = false

    @JvmField var dozeAmount = 0.0f

    @JvmField var interpolatedDozeAmount = 0.0f

    @JvmField var dozeAmountTarget = 0.0f

    @JvmField var leaveOpen = false

    @JvmField var keyguardRequested = false

    var lastSetDozeAmountView: View? = null
        private set

    var lastSetDozeAmountAnimated = false
        private set

    var lastSystemBarAppearance = 0
        private set

    var lastSystemBarBehavior = 0
        private set

    var lastSystemBarRequestedVisibleTypes = 0
        private set

    var lastSystemBarPackageName: String? = null
        private set

    private val _callbacks = mutableSetOf<StatusBarStateController.StateListener>()

    @JvmField val callbacks: Set<StatusBarStateController.StateListener> = _callbacks

    private var fullscreen = false

    override fun start() {}

    override fun getState() = state

    override fun setState(newState: Int, force: Boolean): Boolean {
        val oldState = this.state
        newState != oldState || force || return false

        callbacks.forEach { it.onStatePreChange(oldState, newState) }
        this.lastState = oldState
        this.state = newState
        setUpcomingState(newState)
        callbacks.forEach { it.onStateChanged(newState) }
        callbacks.forEach { it.onStatePostChange() }
        return true
    }

    override fun getCurrentOrUpcomingState() = upcomingState

    override fun setUpcomingState(upcomingState: Int) {
        upcomingState != this.upcomingState || return
        this.upcomingState = upcomingState
        callbacks.forEach { it.onUpcomingStateChanged(upcomingState) }
    }

    override fun isDozing() = dozing

    override fun setIsDozing(dozing: Boolean): Boolean {
        dozing != this.dozing || return false
        this.dozing = dozing
        callbacks.forEach { it.onDozingChanged(dozing) }
        return true
    }

    override fun isExpanded() = expanded

    fun fakeShadeExpansionFullyChanged(expanded: Boolean) {
        expanded != this.expanded || return
        this.expanded = expanded
        callbacks.forEach { it.onExpandedChanged(expanded) }
    }

    override fun isPulsing() = pulsing

    override fun setPulsing(pulsing: Boolean) {
        pulsing != this.pulsing || return
        this.pulsing = pulsing
        callbacks.forEach { it.onPulsingChanged(pulsing) }
    }

    override fun isDreaming() = dreaming

    override fun setIsDreaming(dreaming: Boolean): Boolean {
        dreaming != this.dreaming || return false
        this.dreaming = dreaming
        callbacks.forEach { it.onDreamingChanged(dreaming) }
        return true
    }

    override fun getDozeAmount() = dozeAmount

    override fun setAndInstrumentDozeAmount(view: View?, dozeAmount: Float, animated: Boolean) {
        dozeAmountTarget = dozeAmount
        lastSetDozeAmountView = view
        lastSetDozeAmountAnimated = animated
        if (!animated) {
            this.dozeAmount = dozeAmount
        }
    }

    override fun leaveOpenOnKeyguardHide() = leaveOpen

    override fun setLeaveOpenOnKeyguardHide(leaveOpen: Boolean) {
        this.leaveOpen = leaveOpen
    }

    override fun getInterpolatedDozeAmount() = interpolatedDozeAmount

    fun fakeInterpolatedDozeAmountChanged(interpolatedDozeAmount: Float) {
        this.interpolatedDozeAmount = interpolatedDozeAmount
        callbacks.forEach { it.onDozeAmountChanged(dozeAmount, interpolatedDozeAmount) }
    }

    override fun goingToFullShade() = state == StatusBarState.SHADE && leaveOpen

    override fun fromShadeLocked() = lastState == StatusBarState.SHADE_LOCKED

    override fun isKeyguardRequested(): Boolean = keyguardRequested

    override fun setKeyguardRequested(keyguardRequested: Boolean) {
        this.keyguardRequested = keyguardRequested
    }

    override fun addCallback(listener: StatusBarStateController.StateListener?) {
        _callbacks.add(listener!!)
    }

    override fun addCallback(listener: StatusBarStateController.StateListener?, rank: Int) {
        throw RuntimeException("addCallback with rank unsupported")
    }

    override fun removeCallback(listener: StatusBarStateController.StateListener?) {
        _callbacks.remove(listener!!)
    }
}
