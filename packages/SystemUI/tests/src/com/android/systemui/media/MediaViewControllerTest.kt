package com.android.systemui.media

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.animation.MeasurementInput
import com.android.systemui.util.animation.TransitionLayout
import com.android.systemui.util.animation.TransitionViewState
import junit.framework.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for {@link MediaViewController}.
 */
@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class MediaViewControllerTest : SysuiTestCase() {
    private val configurationController =
            com.android.systemui.statusbar.phone.ConfigurationControllerImpl(context)
    private val mediaHostStatesManager = MediaHostStatesManager()
    private val mediaViewController =
            MediaViewController(context, configurationController, mediaHostStatesManager)
    private val mediaHostStateHolder = MediaHost.MediaHostStateHolder()
    private var transitionLayout = TransitionLayout(context, /* attrs */ null, /* defStyleAttr */ 0)

    @Before
    fun setUp() {
        mediaViewController.attach(transitionLayout, MediaViewController.TYPE.PLAYER)
    }

    @Test
    fun testObtainViewState_applySquishFraction_toTransitionViewState_height() {
        transitionLayout.measureState = TransitionViewState().apply {
            this.height = 100
        }
        mediaHostStateHolder.expansion = 1f
        val widthMeasureSpec = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        val heightMeasureSpec = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY)
        mediaHostStateHolder.measurementInput =
                MeasurementInput(widthMeasureSpec, heightMeasureSpec)

        // Test no squish
        mediaHostStateHolder.squishFraction = 1f
        assertTrue(mediaViewController.obtainViewState(mediaHostStateHolder)!!.height == 100)

        // Test half squish
        mediaHostStateHolder.squishFraction = 0.5f
        assertTrue(mediaViewController.obtainViewState(mediaHostStateHolder)!!.height == 50)
    }
}