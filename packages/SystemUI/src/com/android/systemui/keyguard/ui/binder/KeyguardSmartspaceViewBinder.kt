package com.android.systemui.keyguard.ui.binder

import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.keyguard.ui.view.layout.sections.SmartspaceSection
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.lifecycle.repeatWhenAttached

object KeyguardSmartspaceViewBinder {
    @JvmStatic
    fun bind(
        smartspaceSection: SmartspaceSection,
        keyguardRootView: ConstraintLayout,
        clockViewModel: KeyguardClockViewModel,
    ) {
        keyguardRootView.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                clockViewModel.hasCustomWeatherDataDisplay.collect {
                    val constraintSet = ConstraintSet().apply { clone(keyguardRootView) }
                    smartspaceSection.applyConstraints(constraintSet)
                    constraintSet.applyTo(keyguardRootView)
                }
            }
        }
    }
}
