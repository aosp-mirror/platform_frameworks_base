package com.android.systemui.unfold.updates.hinge

import androidx.core.util.Consumer
import com.android.systemui.statusbar.policy.CallbackController

internal interface HingeAngleProvider : CallbackController<Consumer<Float>> {
    fun start()
    fun stop()
}

const val FULLY_OPEN_DEGREES = 180f
const val FULLY_CLOSED_DEGREES = 0f
