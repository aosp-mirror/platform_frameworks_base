package com.android.systemui.animation

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.testing.ViewUtils
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.filters.SmallTest
import com.android.internal.jank.Cuj
import com.android.internal.policy.DecorView
import com.android.systemui.SysuiTestCase
import com.android.systemui.jank.interactionJankMonitor
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import junit.framework.Assert.assertEquals
import junit.framework.Assert.assertFalse
import junit.framework.Assert.assertNotNull
import junit.framework.Assert.assertNull
import junit.framework.Assert.assertTrue
import org.junit.After
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.any
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class DialogTransitionAnimatorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private lateinit var mDialogTransitionAnimator: DialogTransitionAnimator
    private val attachedViews = mutableSetOf<View>()
    @get:Rule
    val rule: MockitoRule = MockitoJUnit.rule()

    @Before
    fun setUp() {
        mDialogTransitionAnimator = kosmos.dialogTransitionAnimator
    }

    @After
    fun tearDown() {
        runOnMainThreadAndWaitForIdleSync {
            attachedViews.forEach {
                ViewUtils.detachView(it)
            }
        }
    }

    @Test
    fun testShowDialogFromView() {
        // Show the dialog. showFromView() must be called on the main thread with a dialog created
        // on the main thread too.
        val dialog = createAndShowDialog()

        assertTrue(dialog.isShowing)

        // The dialog is now fullscreen.
        val window = checkNotNull(dialog.window)
        val decorView = window.decorView as DecorView
        assertEquals(MATCH_PARENT, window.attributes.width)
        assertEquals(MATCH_PARENT, window.attributes.height)
        assertEquals(MATCH_PARENT, decorView.layoutParams.width)
        assertEquals(MATCH_PARENT, decorView.layoutParams.height)

        // The single DecorView child is a transparent fullscreen view that will dismiss the dialog
        // when clicked.
        assertEquals(1, decorView.childCount)
        val transparentBackground = decorView.getChildAt(0) as ViewGroup
        assertEquals(MATCH_PARENT, transparentBackground.layoutParams.width)
        assertEquals(MATCH_PARENT, transparentBackground.layoutParams.height)

        // The single transparent background child is a fake window with the same size and
        // background as the dialog initially had.
        assertEquals(1, transparentBackground.childCount)
        val dialogContentWithBackground = transparentBackground.getChildAt(0) as ViewGroup
        assertEquals(TestDialog.DIALOG_WIDTH, dialogContentWithBackground.layoutParams.width)
        assertEquals(TestDialog.DIALOG_HEIGHT, dialogContentWithBackground.layoutParams.height)
        assertEquals(dialog.windowBackground, dialogContentWithBackground.background)

        // The dialog content is inside this fake window view.
        assertNotNull(
                dialogContentWithBackground.findViewByPredicate { it === dialog.contentView }
        )

        // Clicking the transparent background should dismiss the dialog.
        runOnMainThreadAndWaitForIdleSync {
            transparentBackground.performClick()
        }
        assertFalse(dialog.isShowing)
    }

    @Test
    fun testStackedDialogsDismissesAll() {
        val firstDialog = createAndShowDialog()
        val secondDialog = createDialogAndShowFromDialog(firstDialog)

        assertTrue(firstDialog.isShowing)
        assertTrue(secondDialog.isShowing)
        runOnMainThreadAndWaitForIdleSync {
            mDialogTransitionAnimator.dismissStack(secondDialog)
        }

        assertFalse(firstDialog.isShowing)
        assertFalse(secondDialog.isShowing)
    }

    @Test
    fun testActivityTransitionControllerFromDialog() {
        val firstDialog = createAndShowDialog()
        val secondDialog = createDialogAndShowFromDialog(firstDialog)

        val controller = mDialogTransitionAnimator
                .createActivityTransitionController(secondDialog.contentView)!!

        // The dialog shouldn't be dismissable during the animation.
        runOnMainThreadAndWaitForIdleSync {
            controller.onTransitionAnimationStart(isExpandingFullyAbove = true)
            secondDialog.dismiss()
        }
        assertTrue(secondDialog.isShowing)

        // Both dialogs should be dismissed at the end of the animation.
        runOnMainThreadAndWaitForIdleSync {
            controller.onTransitionAnimationEnd(isExpandingFullyAbove = true)
        }
        assertFalse(firstDialog.isShowing)
        assertFalse(secondDialog.isShowing)
    }

    @Test
    fun testActivityLaunchFromHiddenDialog() {
        val dialog = createAndShowDialog()
        runOnMainThreadAndWaitForIdleSync {
            dialog.hide()
        }
        assertNull(mDialogTransitionAnimator.createActivityTransitionController(dialog.contentView))
    }

    @Test
    fun testActivityLaunchWhenLockedWithoutAlternateAuth() {
        val dialogTransitionAnimator =
                fakeDialogTransitionAnimator(
                        isUnlocked = false,
                        isShowingAlternateAuthOnUnlock = false,
                        interactionJankMonitor = kosmos.interactionJankMonitor)
        val dialog = createAndShowDialog(dialogTransitionAnimator)
        assertNull(dialogTransitionAnimator.createActivityTransitionController(dialog.contentView))
    }

    @Test
    fun testActivityLaunchWhenLockedWithAlternateAuth() {
        val dialogTransitionAnimator = fakeDialogTransitionAnimator(
                isUnlocked = false,
                isShowingAlternateAuthOnUnlock = true,
                interactionJankMonitor = kosmos.interactionJankMonitor
        )
        val dialog = createAndShowDialog(dialogTransitionAnimator)
        assertNotNull(
                dialogTransitionAnimator.createActivityTransitionController(dialog.contentView)
        )
    }

    @Test
    fun testDialogAnimationIsChangedByAnimator() {
        // Important: the power menu animation relies on this behavior to know when to animate (see
        // http://ag/16774605).
        val dialog = runOnMainThreadAndWaitForIdleSync { TestDialog(context) }
        val window = checkNotNull(dialog.window)
        window.setWindowAnimations(0)
        assertEquals(0, window.attributes.windowAnimations)

        val touchSurface = createTouchSurface()
        runOnMainThreadAndWaitForIdleSync {
            mDialogTransitionAnimator.showFromView(dialog, touchSurface)
        }
        assertNotEquals(0, window.attributes.windowAnimations)
    }

    @Test
    fun testCujSpecificationLogsInteraction() {
        val touchSurface = createTouchSurface()
        runOnMainThreadAndWaitForIdleSync {
            val dialog = TestDialog(context)
            mDialogTransitionAnimator.showFromView(
                    dialog,
                    touchSurface,
                    cuj = DialogCuj(Cuj.CUJ_SHADE_DIALOG_OPEN)
            )
        }

        verify(kosmos.interactionJankMonitor).begin(any())
        verify(kosmos.interactionJankMonitor).end(Cuj.CUJ_SHADE_DIALOG_OPEN)
    }

    @Test
    fun testShowFromDialogCujSpecificationLogsInteraction() {
        val firstDialog = createAndShowDialog()
        runOnMainThreadAndWaitForIdleSync {
            val dialog = TestDialog(context)
            mDialogTransitionAnimator.showFromDialog(
                    dialog,
                    firstDialog,
                    cuj = DialogCuj(Cuj.CUJ_USER_DIALOG_OPEN)
            )
            dialog
        }
        verify(kosmos.interactionJankMonitor).begin(any())
        verify(kosmos.interactionJankMonitor).end(Cuj.CUJ_USER_DIALOG_OPEN)
    }

    @Test
    fun testAnimationDoesNotChangeLaunchableViewVisibility_viewVisible() {
        val touchSurface = createTouchSurface()

        // View is VISIBLE when starting the animation.
        runOnMainThreadAndWaitForIdleSync { touchSurface.visibility = View.VISIBLE }

        // View is invisible while the dialog is shown.
        val dialog = showDialogFromView(touchSurface)
        assertThat(touchSurface.visibility).isEqualTo(View.INVISIBLE)

        // View is visible again when the dialog is dismissed.
        runOnMainThreadAndWaitForIdleSync { dialog.dismiss() }
        assertThat(touchSurface.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun testAnimationDoesNotChangeLaunchableViewVisibility_viewInvisible() {
        val touchSurface = createTouchSurface()

        // View is INVISIBLE when starting the animation.
        runOnMainThreadAndWaitForIdleSync { touchSurface.visibility = View.INVISIBLE }

        // View is INVISIBLE while the dialog is shown.
        val dialog = showDialogFromView(touchSurface)
        assertThat(touchSurface.visibility).isEqualTo(View.INVISIBLE)

        // View is invisible like it was before showing the dialog.
        runOnMainThreadAndWaitForIdleSync { dialog.dismiss() }
        assertThat(touchSurface.visibility).isEqualTo(View.INVISIBLE)
    }

    @Test
    fun testAnimationDoesNotChangeLaunchableViewVisibility_viewVisibleThenGone() {
        val touchSurface = createTouchSurface()

        // View is VISIBLE when starting the animation.
        runOnMainThreadAndWaitForIdleSync { touchSurface.visibility = View.VISIBLE }

        // View is INVISIBLE while the dialog is shown.
        val dialog = showDialogFromView(touchSurface)
        assertThat(touchSurface.visibility).isEqualTo(View.INVISIBLE)

        // Some external call makes the View GONE. It remains INVISIBLE while the dialog is shown,
        // as all visibility changes should be blocked.
        runOnMainThreadAndWaitForIdleSync { touchSurface.visibility = View.GONE }
        assertThat(touchSurface.visibility).isEqualTo(View.INVISIBLE)

        // View is restored to GONE once the dialog is dismissed.
        runOnMainThreadAndWaitForIdleSync { dialog.dismiss() }
        assertThat(touchSurface.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun creatingControllerFromNormalViewThrows() {
        assertThrows(IllegalArgumentException::class.java) {
            DialogTransitionAnimator.Controller.fromView(FrameLayout(mContext))
        }
    }

    @Test
    fun showFromDialogDoesNotCrashWhenShownFromRandomDialog() {
        val dialog = createDialogAndShowFromDialog(animateFrom = TestDialog(context))
        dialog.dismiss()
    }

    private fun createAndShowDialog(
            animator: DialogTransitionAnimator = mDialogTransitionAnimator,
    ): TestDialog {
        val touchSurface = createTouchSurface()
        return showDialogFromView(touchSurface, animator)
    }

    private fun createTouchSurface(): View {
        return runOnMainThreadAndWaitForIdleSync {
            val touchSurfaceRoot = LinearLayout(context)
            val touchSurface = TouchSurfaceView(context)
            touchSurfaceRoot.addView(touchSurface)

            // We need to attach the root to the window manager otherwise the exit animation will
            // be skipped.
            ViewUtils.attachView(touchSurfaceRoot)
            attachedViews.add(touchSurfaceRoot)

            touchSurface
        }
    }

    private fun showDialogFromView(
            touchSurface: View,
            animator: DialogTransitionAnimator = mDialogTransitionAnimator,
    ): TestDialog {
        return runOnMainThreadAndWaitForIdleSync {
            val dialog = TestDialog(context)
            animator.showFromView(dialog, touchSurface)
            dialog
        }
    }

    private fun createDialogAndShowFromDialog(animateFrom: Dialog): TestDialog {
        return runOnMainThreadAndWaitForIdleSync {
            val dialog = TestDialog(context)
            mDialogTransitionAnimator.showFromDialog(dialog, animateFrom)
            dialog
        }
    }

    private fun <T : Any> runOnMainThreadAndWaitForIdleSync(f: () -> T): T {
        lateinit var result: T
        context.mainExecutor.execute {
            result = f()
        }
        waitForIdleSync()
        return result
    }

    private class TouchSurfaceView(context: Context) : FrameLayout(context), LaunchableView {
        private val delegate =
                LaunchableViewDelegate(
                        this,
                        superSetVisibility = { super.setVisibility(it) },
                )

        override fun setShouldBlockVisibilityChanges(block: Boolean) {
            delegate.setShouldBlockVisibilityChanges(block)
        }

        override fun setVisibility(visibility: Int) {
            delegate.setVisibility(visibility)
        }
    }

    private class TestDialog(context: Context) : Dialog(context) {
        companion object {
            const val DIALOG_WIDTH = 100
            const val DIALOG_HEIGHT = 200
        }

        val contentView = View(context)
        val windowBackground = ColorDrawable(Color.RED)

        init {
            // We need to set the window type for dialogs shown by SysUI, otherwise WM will throw.
            checkNotNull(window).setType(WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            setContentView(contentView)

            val window = checkNotNull(window)
            window.setLayout(DIALOG_WIDTH, DIALOG_HEIGHT)
            window.setBackgroundDrawable(windowBackground)
        }
    }
}
