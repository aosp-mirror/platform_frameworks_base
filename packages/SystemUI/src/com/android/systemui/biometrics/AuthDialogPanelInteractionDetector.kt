package com.android.systemui.biometrics

import android.annotation.AnyThread
import android.annotation.MainThread
import android.util.Log
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.shade.ShadeExpansionChangeEvent
import com.android.systemui.shade.ShadeExpansionStateManager
import java.util.concurrent.Executor
import javax.inject.Inject

class AuthDialogPanelInteractionDetector
@Inject
constructor(
    private val shadeExpansionStateManager: ShadeExpansionStateManager,
    @Main private val mainExecutor: Executor,
) {
    private var action: Action? = null

    @MainThread
    fun enable(onPanelInteraction: Runnable) {
        if (action == null) {
            action = Action(onPanelInteraction)
            shadeExpansionStateManager.addShadeExpansionListener(this::onPanelExpansionChanged)
        } else {
            Log.e(TAG, "Already enabled")
        }
    }

    @MainThread
    fun disable() {
        if (action != null) {
            Log.i(TAG, "Disable dectector")
            action = null
            shadeExpansionStateManager.removeExpansionListener(this::onPanelExpansionChanged)
        }
    }

    @AnyThread
    private fun onPanelExpansionChanged(event: ShadeExpansionChangeEvent) =
        mainExecutor.execute {
            action?.let {
                if (event.tracking || (event.expanded && event.fraction > 0)) {
                    Log.i(TAG, "Detected panel interaction, event: $event")
                    it.onPanelInteraction.run()
                    disable()
                }
            }
        }
}

private data class Action(val onPanelInteraction: Runnable)

private const val TAG = "AuthDialogPanelInteractionDetector"
