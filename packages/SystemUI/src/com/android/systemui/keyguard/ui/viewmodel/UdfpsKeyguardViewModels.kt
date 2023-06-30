package com.android.systemui.keyguard.ui.viewmodel

import android.graphics.Rect
import com.android.systemui.biometrics.UdfpsKeyguardView
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.keyguard.ui.binder.UdfpsKeyguardViewBinder
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi

@ExperimentalCoroutinesApi
@SysUISingleton
class UdfpsKeyguardViewModels
@Inject
constructor(
    private val viewModel: UdfpsKeyguardViewModel,
    private val internalViewModel: UdfpsKeyguardInternalViewModel,
    private val aodViewModel: UdfpsAodViewModel,
    private val lockscreenFingerprintViewModel: FingerprintViewModel,
    private val lockscreenBackgroundViewModel: BackgroundViewModel,
) {

    fun setSensorBounds(sensorBounds: Rect) {
        viewModel.sensorBounds = sensorBounds
    }

    fun bindViews(view: UdfpsKeyguardView) {
        UdfpsKeyguardViewBinder.bind(
            view,
            viewModel,
            internalViewModel,
            aodViewModel,
            lockscreenFingerprintViewModel,
            lockscreenBackgroundViewModel
        )
    }
}
