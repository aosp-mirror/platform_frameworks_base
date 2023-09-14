package com.android.systemui.statusbar

import android.content.Context
import android.content.res.Configuration
import android.util.IndentingPrintWriter
import com.android.systemui.Dumpable
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.policy.ConfigurationController
import com.android.systemui.statusbar.policy.SplitShadeStateController
import java.io.PrintWriter

/** An abstract implementation of a class that controls the lockscreen to shade transition. */
abstract class AbstractLockscreenShadeTransitionController(
    protected val context: Context,
    configurationController: ConfigurationController,
    dumpManager: DumpManager,
    private val splitShadeStateController: SplitShadeStateController
) : Dumpable {

    protected var useSplitShade = false

    /**
     * The amount of pixels that the user has dragged down during the shade transition on
     * lockscreen.
     */
    var dragDownAmount = 0f
        set(value) {
            if (value == field) {
                return
            }
            field = value
            onDragDownAmountChanged(value)
        }

    init {
        updateResourcesInternal()
        configurationController.addCallback(
            object : ConfigurationController.ConfigurationListener {
                override fun onConfigChanged(newConfig: Configuration?) {
                    updateResourcesInternal()
                }
            })
        @Suppress("LeakingThis")
        dumpManager.registerDumpable(this)
    }

    private fun updateResourcesInternal() {
        useSplitShade = splitShadeStateController
                .shouldUseSplitNotificationShade(context.resources)
        updateResources()
    }

    protected abstract fun updateResources()

    protected abstract fun onDragDownAmountChanged(dragDownAmount: Float)

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        dump(IndentingPrintWriter(pw, /* singleIndent= */ "  "))
    }

    abstract fun dump(pw: IndentingPrintWriter)
}
