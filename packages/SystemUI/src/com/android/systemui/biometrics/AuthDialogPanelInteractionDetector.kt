package com.android.systemui.biometrics

import android.annotation.MainThread
import android.util.Log
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import dagger.Lazy
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AuthDialogPanelInteractionDetector
@Inject
constructor(
    @Application private val scope: CoroutineScope,
    private val shadeInteractorLazy: Lazy<ShadeInteractor>,
) {
    private var shadeExpansionCollectorJob: Job? = null

    @MainThread
    fun enable(onShadeInteraction: Runnable) {
        if (shadeExpansionCollectorJob == null) {
            shadeExpansionCollectorJob =
                scope.launch {
                    // wait for it to emit true once
                    shadeInteractorLazy.get().anyExpanding.first { it }
                    onShadeInteraction.run()
                }
            shadeExpansionCollectorJob?.invokeOnCompletion { shadeExpansionCollectorJob = null }
        } else {
            Log.e(TAG, "Already enabled")
        }
    }

    @MainThread
    fun disable() {
        Log.i(TAG, "Disable detector")
        shadeExpansionCollectorJob?.cancel()
    }
}

private const val TAG = "AuthDialogPanelInteractionDetector"
