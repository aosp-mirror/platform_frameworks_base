package com.android.systemui.testing.screenshot

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.junit.Assert.assertEquals
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** A rule for View screenshot diff tests. */
class ViewScreenshotTestRule(testSpec: ScreenshotTestSpec) : TestRule {
    private val activityRule = ActivityScenarioRule(ScreenshotActivity::class.java)
    private val screenshotRule = ScreenshotTestRule(testSpec)

    private val delegate = RuleChain.outerRule(screenshotRule).around(activityRule)

    override fun apply(base: Statement, description: Description): Statement {
        return delegate.apply(base, description)
    }

    /**
     * Compare the content of the view provided by [viewProvider] with the golden image identified
     * by [goldenIdentifier] in the context of [testSpec].
     */
    fun screenshotTest(
        goldenIdentifier: String,
        layoutParams: LayoutParams =
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        viewProvider: (Activity) -> View,
    ) {
        activityRule.scenario.onActivity { activity ->
            // Make sure that the activity draws full screen and fits the whole display instead of
            // the system bars.
            activity.window.setDecorFitsSystemWindows(false)
            activity.setContentView(viewProvider(activity), layoutParams)
        }

        // We call onActivity again because it will make sure that our Activity is done measuring,
        // laying out and drawing its content (that we set in the previous onActivity lambda).
        activityRule.scenario.onActivity { activity ->
            // Check that the content is what we expected.
            val content = activity.requireViewById<ViewGroup>(android.R.id.content)
            assertEquals(1, content.childCount)
            screenshotRule.screenshotTest(goldenIdentifier, content.getChildAt(0))
        }
    }

    /**
     * Compare the content of the dialog provided by [dialogProvider] with the golden image
     * identified by [goldenIdentifier] in the context of [testSpec].
     */
    fun dialogScreenshotTest(
        goldenIdentifier: String,
        dialogProvider: (Activity) -> Dialog,
    ) {
        var dialog: Dialog? = null
        activityRule.scenario.onActivity { activity ->
            // Make sure that the dialog draws full screen and fits the whole display instead of the
            // system bars.
            dialog =
                dialogProvider(activity).apply {
                    window.setDecorFitsSystemWindows(false)
                    show()
                }
        }

        // We call onActivity again because it will make sure that our Dialog is done measuring,
        // laying out and drawing its content (that we set in the previous onActivity lambda).
        activityRule.scenario.onActivity {
            // Check that the content is what we expected.
            val dialog = dialog ?: error("dialog is null")
            screenshotRule.screenshotTest(goldenIdentifier, dialog.window.decorView)
        }
    }
}
