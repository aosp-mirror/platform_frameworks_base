package com.android.systemui.animation

import android.app.ActivityManager
import android.view.SurfaceControl
import android.view.View
import android.view.ViewRootImpl

/** A util class to synchronize 2 view roots. */
// TODO(b/200284684): Remove this class.
object ViewRootSync {
    // TODO(b/217621394): Remove special handling for low-RAM devices after animation sync is fixed
    private val forceDisableSynchronization = ActivityManager.isLowRamDeviceStatic()

    /**
     * Synchronize the next draw between the view roots of [view] and [otherView], then run [then].
     *
     * Note that in some cases, the synchronization might not be possible (e.g. WM consumed the
     * next transactions) or disabled (temporarily, on low ram devices). In this case, [then] will
     * be called without synchronizing.
     */
    fun synchronizeNextDraw(
        view: View,
        otherView: View,
        then: () -> Unit
    ) {
        if (forceDisableSynchronization ||
            !view.isAttachedToWindow || view.viewRootImpl == null ||
            !otherView.isAttachedToWindow || otherView.viewRootImpl == null ||
            view.viewRootImpl == otherView.viewRootImpl) {
            // No need to synchronize if either the touch surface or dialog view is not attached
            // to a window.
            then()
            return
        }

        // Consume the next frames of both view roots to make sure the ghost view is drawn at
        // exactly the same time as when the touch surface is made invisible.
        var remainingTransactions = 0
        val mergedTransactions = SurfaceControl.Transaction()

        fun onTransaction(transaction: SurfaceControl.Transaction?) {
            remainingTransactions--
            transaction?.let { mergedTransactions.merge(it) }

            if (remainingTransactions == 0) {
                mergedTransactions.apply()
                then()
            }
        }

        fun consumeNextDraw(viewRootImpl: ViewRootImpl) {
            if (viewRootImpl.consumeNextDraw(::onTransaction)) {
                remainingTransactions++

                // Make sure we trigger a traversal.
                viewRootImpl.view.invalidate()
            }
        }

        consumeNextDraw(view.viewRootImpl)
        consumeNextDraw(otherView.viewRootImpl)

        if (remainingTransactions == 0) {
            then()
        }
    }

    /**
     * A Java-friendly API for [synchronizeNextDraw].
     */
    @JvmStatic
    fun synchronizeNextDraw(view: View, otherView: View, then: Runnable) {
        synchronizeNextDraw(view, otherView, then::run)
    }
}