package com.android.systemui.animation

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.ViewUtils
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class DialogLaunchAnimatorTest : SysuiTestCase() {
    private val launchAnimator = LaunchAnimator(context, isForTesting = true)
    private val hostDialogprovider = TestHostDialogProvider()
    private val dialogLaunchAnimator =
        DialogLaunchAnimator(context, launchAnimator, hostDialogprovider)

    @Test
    fun testShowDialogFromView() {
        // Show the dialog. showFromView() must be called on the main thread with a dialog created
        // on the main thread too.
        val (dialog, hostDialog) = runOnMainThreadAndWaitForIdleSync {
            val touchSurfaceRoot = LinearLayout(context)
            val touchSurface = View(context)
            touchSurfaceRoot.addView(touchSurface)

            // We need to attach the root to the window manager otherwise the exit animation will
            // be skipped
            ViewUtils.attachView(touchSurfaceRoot)

            val dialog = TestDialog(context)
            val hostDialog =
                dialogLaunchAnimator.showFromView(dialog, touchSurface) as TestHostDialog
            dialog to hostDialog
        }

        // Only the host dialog is actually showing.
        assertTrue(hostDialog.isShowing)
        assertFalse(dialog.isShowing)

        // The dialog onStart() method was called but not onStop().
        assertTrue(dialog.onStartCalled)
        assertFalse(dialog.onStopCalled)

        // The dialog content has been stolen and is shown inside the host dialog.
        val hostDialogContent = hostDialog.findViewById<ViewGroup>(android.R.id.content)
        assertEquals(0, dialog.findViewById<ViewGroup>(android.R.id.content).childCount)
        assertEquals(1, hostDialogContent.childCount)

        val hostDialogRoot = hostDialogContent.getChildAt(0) as ViewGroup
        assertEquals(1, hostDialogRoot.childCount)
        assertEquals(dialog.contentView, hostDialogRoot.getChildAt(0))

        // If we are dozing, the host dialog window also fades out.
        runOnMainThreadAndWaitForIdleSync { dialogLaunchAnimator.onDozeAmountChanged(0.5f) }
        assertTrue(hostDialog.window!!.decorView.alpha < 1f)

        // Hiding/showing/dismissing the dialog should hide/show/dismiss the host dialog given that
        // it's a ListenableDialog.
        runOnMainThreadAndWaitForIdleSync { dialog.hide() }
        assertFalse(hostDialog.isShowing)
        assertFalse(dialog.isShowing)

        runOnMainThreadAndWaitForIdleSync { dialog.show() }
        assertTrue(hostDialog.isShowing)
        assertFalse(dialog.isShowing)

        assertFalse(dialog.onStopCalled)
        runOnMainThreadAndWaitForIdleSync { dialog.dismiss() }
        assertFalse(hostDialog.isShowing)
        assertFalse(dialog.isShowing)
        assertTrue(hostDialog.wasDismissed)
        assertTrue(dialog.onStopCalled)
    }

    private fun <T : Any> runOnMainThreadAndWaitForIdleSync(f: () -> T): T {
        lateinit var result: T
        context.mainExecutor.execute {
            result = f()
        }
        waitForIdleSync()
        return result
    }

    private class TestHostDialogProvider : HostDialogProvider {
        override fun createHostDialog(
            context: Context,
            theme: Int,
            onCreateCallback: () -> Unit,
            dismissOverride: (() -> Unit) -> Unit
        ): Dialog = TestHostDialog(context, onCreateCallback, dismissOverride)
    }

    private class TestHostDialog(
        context: Context,
        private val onCreateCallback: () -> Unit,
        private val dismissOverride: (() -> Unit) -> Unit
    ) : Dialog(context) {
        var wasDismissed = false

        init {
            // We need to set the window type for dialogs shown by SysUI, otherwise WM will throw.
            window.setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            onCreateCallback()
        }

        override fun dismiss() {
            dismissOverride {
                super.dismiss()
                wasDismissed = true
            }
        }
    }

    private class TestDialog(context: Context) : Dialog(context), ListenableDialog {
        private val listeners = hashSetOf<DialogListener>()
        val contentView = View(context)
        var onStartCalled = false
        var onStopCalled = false

        init {
            // We need to set the window type for dialogs shown by SysUI, otherwise WM will throw.
            window.setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(contentView)
        }

        override fun onStart() {
            super.onStart()
            onStartCalled = true
        }

        override fun onStop() {
            super.onStart()
            onStopCalled = true
        }

        override fun addListener(listener: DialogListener) {
            listeners.add(listener)
        }

        override fun removeListener(listener: DialogListener) {
            listeners.remove(listener)
        }

        override fun dismiss() {
            super.dismiss()
            notifyListeners { onDismiss() }
        }

        override fun hide() {
            super.hide()
            notifyListeners { onHide() }
        }

        override fun show() {
            super.show()
            notifyListeners { onShow() }
        }

        private fun notifyListeners(notify: DialogListener.() -> Unit) {
            for (listener in HashSet(listeners)) {
                listener.notify()
            }
        }
    }
}