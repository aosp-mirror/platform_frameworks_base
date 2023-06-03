package com.android.systemui.shade.transition

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.policy.FakeConfigurationController
import com.google.common.truth.Expect
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class LargeScreenShadeInterpolatorImplTest : SysuiTestCase() {
    @get:Rule val expect: Expect = Expect.create()

    private val portraitShadeInterpolator = LargeScreenPortraitShadeInterpolator()
    private val splitShadeInterpolator = SplitShadeInterpolator()
    private val configurationController = FakeConfigurationController()
    private val impl =
        LargeScreenShadeInterpolatorImpl(
            configurationController,
            context,
            splitShadeInterpolator,
            portraitShadeInterpolator
        )

    @Test
    fun getBehindScrimAlpha_inSplitShade_usesSplitShadeValue() {
        setSplitShadeEnabled(true)

        assertInterpolation(
            actual = { fraction -> impl.getBehindScrimAlpha(fraction) },
            expected = { fraction -> splitShadeInterpolator.getBehindScrimAlpha(fraction) }
        )
    }

    @Test
    fun getBehindScrimAlpha_inPortraitShade_usesPortraitShadeValue() {
        setSplitShadeEnabled(false)

        assertInterpolation(
            actual = { fraction -> impl.getBehindScrimAlpha(fraction) },
            expected = { fraction -> portraitShadeInterpolator.getBehindScrimAlpha(fraction) }
        )
    }

    @Test
    fun getNotificationScrimAlpha_inSplitShade_usesSplitShadeValue() {
        setSplitShadeEnabled(true)

        assertInterpolation(
            actual = { fraction -> impl.getNotificationScrimAlpha(fraction) },
            expected = { fraction -> splitShadeInterpolator.getNotificationScrimAlpha(fraction) }
        )
    }
    @Test
    fun getNotificationScrimAlpha_inPortraitShade_usesPortraitShadeValue() {
        setSplitShadeEnabled(false)

        assertInterpolation(
            actual = { fraction -> impl.getNotificationScrimAlpha(fraction) },
            expected = { fraction -> portraitShadeInterpolator.getNotificationScrimAlpha(fraction) }
        )
    }

    @Test
    fun getNotificationContentAlpha_inSplitShade_usesSplitShadeValue() {
        setSplitShadeEnabled(true)

        assertInterpolation(
            actual = { fraction -> impl.getNotificationContentAlpha(fraction) },
            expected = { fraction -> splitShadeInterpolator.getNotificationContentAlpha(fraction) }
        )
    }

    @Test
    fun getNotificationContentAlpha_inPortraitShade_usesPortraitShadeValue() {
        setSplitShadeEnabled(false)

        assertInterpolation(
            actual = { fraction -> impl.getNotificationContentAlpha(fraction) },
            expected = { fraction ->
                portraitShadeInterpolator.getNotificationContentAlpha(fraction)
            }
        )
    }

    @Test
    fun getNotificationFooterAlpha_inSplitShade_usesSplitShadeValue() {
        setSplitShadeEnabled(true)

        assertInterpolation(
            actual = { fraction -> impl.getNotificationFooterAlpha(fraction) },
            expected = { fraction -> splitShadeInterpolator.getNotificationFooterAlpha(fraction) }
        )
    }
    @Test
    fun getNotificationFooterAlpha_inPortraitShade_usesPortraitShadeValue() {
        setSplitShadeEnabled(false)

        assertInterpolation(
            actual = { fraction -> impl.getNotificationFooterAlpha(fraction) },
            expected = { fraction ->
                portraitShadeInterpolator.getNotificationFooterAlpha(fraction)
            }
        )
    }

    @Test
    fun getQsAlpha_inSplitShade_usesSplitShadeValue() {
        setSplitShadeEnabled(true)

        assertInterpolation(
            actual = { fraction -> impl.getQsAlpha(fraction) },
            expected = { fraction -> splitShadeInterpolator.getQsAlpha(fraction) }
        )
    }
    @Test
    fun getQsAlpha_inPortraitShade_usesPortraitShadeValue() {
        setSplitShadeEnabled(false)

        assertInterpolation(
            actual = { fraction -> impl.getQsAlpha(fraction) },
            expected = { fraction -> portraitShadeInterpolator.getQsAlpha(fraction) }
        )
    }

    private fun setSplitShadeEnabled(enabled: Boolean) {
        overrideResource(R.bool.config_use_split_notification_shade, enabled)
        configurationController.notifyConfigurationChanged()
    }

    private fun assertInterpolation(
        actual: (fraction: Float) -> Float,
        expected: (fraction: Float) -> Float
    ) {
        for (i in 0..10) {
            val fraction = i / 10f
            expect.that(actual(fraction)).isEqualTo(expected(fraction))
        }
    }
}
