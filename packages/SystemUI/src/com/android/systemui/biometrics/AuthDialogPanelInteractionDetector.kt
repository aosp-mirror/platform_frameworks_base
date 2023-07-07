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
    private var panelState: Int = -1

    @MainThread
    fun enable(onPanelInteraction: Runnable) {
        if (action == null) {
            action = Action(onPanelInteraction)
            shadeExpansionStateManager.addStateListener(this::onPanelStateChanged)
            shadeExpansionStateManager.addExpansionListener(this::onPanelExpansionChanged)
        } else {
            Log.e(TAG, "Already enabled")
        }
    }

    @MainThread
    fun disable() {
        if (action != null) {
            Log.i(TAG, "Disable dectector")
            action = null
            panelState = -1
            shadeExpansionStateManager.removeStateListener(this::onPanelStateChanged)
            shadeExpansionStateManager.removeExpansionListener(this::onPanelExpansionChanged)
        }
    }

    @AnyThread
    private fun onPanelExpansionChanged(event: ShadeExpansionChangeEvent) =
        mainExecutor.execute {
            action?.let {
                if (event.tracking || (event.expanded && event.fraction > 0 && panelState == 1)) {
                    Log.i(TAG, "onPanelExpansionChanged, event: $event")
                    it.onPanelInteraction.run()
                    disable()
                }
            }
        }

    @AnyThread
    private fun onPanelStateChanged(state: Int) =
        mainExecutor.execute {
            // When device owner set screen lock type as Swipe, and install work profile with
            // pin/pattern/password & fingerprint or face, if work profile allow user to verify
            // by BP, it is possible that BP will be displayed when keyguard is closing, in this
            // case event.expanded = true and event.fraction > 0, so BP will be closed, adding
            // panel state into consideration is workaround^2, this workaround works because
            // onPanelStateChanged is earlier than onPanelExpansionChanged

            // we don't want to close BP in below case
            //
            // |      Action       |  tracking  |  expanded  |  fraction  |  panelState  |
            // |      HeadsUp      |    NA      |     NA     |     NA     |      1       |
            // |   b/285111529     |   false    |    true    |    > 0     |      2       |

            // Note: HeadsUp behavior was changed, so we can't got onPanelExpansionChanged now
            panelState = state
            Log.i(TAG, "onPanelStateChanged, state: $state")
        }
}

private data class Action(val onPanelInteraction: Runnable)

private const val TAG = "AuthDialogPanelInteractionDetector"
