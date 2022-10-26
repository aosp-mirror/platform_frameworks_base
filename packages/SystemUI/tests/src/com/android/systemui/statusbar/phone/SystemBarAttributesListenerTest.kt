package com.android.systemui.statusbar.phone

import android.graphics.Rect
import android.testing.AndroidTestingRunner
import android.view.Display
import android.view.InsetsVisibilities
import android.view.WindowInsetsController
import android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
import android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
import android.view.WindowInsetsController.APPEARANCE_LOW_PROFILE_BARS
import android.view.WindowInsetsController.Appearance
import androidx.test.filters.SmallTest
import com.android.internal.statusbar.LetterboxDetails
import com.android.internal.view.AppearanceRegion
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.SysuiStatusBarStateController
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
class SystemBarAttributesListenerTest : SysuiTestCase() {

    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var lightBarController: LightBarController
    @Mock private lateinit var statusBarStateController: SysuiStatusBarStateController
    @Mock private lateinit var letterboxAppearanceCalculator: LetterboxAppearanceCalculator
    @Mock private lateinit var centralSurfaces: CentralSurfaces

    private lateinit var sysBarAttrsListener: SystemBarAttributesListener

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        whenever(
                letterboxAppearanceCalculator.getLetterboxAppearance(
                    anyInt(), anyObject(), anyObject()))
            .thenReturn(TEST_LETTERBOX_APPEARANCE)

        sysBarAttrsListener =
            SystemBarAttributesListener(
                centralSurfaces,
                letterboxAppearanceCalculator,
                statusBarStateController,
                lightBarController,
                dumpManager)
    }

    @Test
    fun onSysBarAttrsChanged_forwardsAppearanceToCentralSurfaces() {
        val appearance = APPEARANCE_LIGHT_STATUS_BARS or APPEARANCE_LIGHT_NAVIGATION_BARS

        changeSysBarAttrs(appearance)

        verify(centralSurfaces).setAppearance(appearance)
    }

    @Test
    fun onSysBarAttrsChanged_forwardsLetterboxAppearanceToCentralSurfaces() {
        changeSysBarAttrs(TEST_APPEARANCE, TEST_LETTERBOX_DETAILS)

        verify(centralSurfaces).setAppearance(TEST_LETTERBOX_APPEARANCE.appearance)
    }

    @Test
    fun onSysBarAttrsChanged_noLetterbox_forwardsOriginalAppearanceToCtrlSrfcs() {
        changeSysBarAttrs(TEST_APPEARANCE, arrayOf<LetterboxDetails>())

        verify(centralSurfaces).setAppearance(TEST_APPEARANCE)
    }

    @Test
    fun onSysBarAttrsChanged_forwardsAppearanceToStatusBarStateController() {
        changeSysBarAttrs(TEST_APPEARANCE)

        verify(statusBarStateController)
            .setSystemBarAttributes(eq(TEST_APPEARANCE), anyInt(), any(), any())
    }

    @Test
    fun onSysBarAttrsChanged_forwardsLetterboxAppearanceToStatusBarStateCtrl() {
        changeSysBarAttrs(TEST_APPEARANCE, TEST_LETTERBOX_DETAILS)

        verify(statusBarStateController)
            .setSystemBarAttributes(
                eq(TEST_LETTERBOX_APPEARANCE.appearance), anyInt(), any(), any())
    }

    @Test
    fun onSysBarAttrsChanged_forwardsAppearanceToLightBarController() {
        changeSysBarAttrs(TEST_APPEARANCE, TEST_APPEARANCE_REGIONS)

        verify(lightBarController)
            .onStatusBarAppearanceChanged(
                eq(TEST_APPEARANCE_REGIONS), anyBoolean(), anyInt(), anyBoolean())
    }

    @Test
    fun onSysBarAttrsChanged_forwardsLetterboxAppearanceToLightBarController() {
        changeSysBarAttrs(TEST_APPEARANCE, TEST_APPEARANCE_REGIONS, TEST_LETTERBOX_DETAILS)

        verify(lightBarController)
            .onStatusBarAppearanceChanged(
                eq(TEST_LETTERBOX_APPEARANCE.appearanceRegions),
                anyBoolean(),
                anyInt(),
                anyBoolean())
    }

    @Test
    fun onStatusBarBoundsChanged_forwardsLetterboxAppearanceToStatusBarStateController() {
        changeSysBarAttrs(TEST_APPEARANCE, TEST_APPEARANCE_REGIONS, TEST_LETTERBOX_DETAILS)
        reset(centralSurfaces, lightBarController, statusBarStateController)

        sysBarAttrsListener.onStatusBarBoundsChanged()

        verify(statusBarStateController)
            .setSystemBarAttributes(
                eq(TEST_LETTERBOX_APPEARANCE.appearance), anyInt(), any(), any())
    }

    @Test
    fun onStatusBarBoundsChanged_forwardsLetterboxAppearanceToLightBarController() {
        changeSysBarAttrs(TEST_APPEARANCE, TEST_APPEARANCE_REGIONS, TEST_LETTERBOX_DETAILS)
        reset(centralSurfaces, lightBarController, statusBarStateController)

        sysBarAttrsListener.onStatusBarBoundsChanged()

        verify(lightBarController)
            .onStatusBarAppearanceChanged(
                eq(TEST_LETTERBOX_APPEARANCE.appearanceRegions),
                anyBoolean(),
                anyInt(),
                anyBoolean())
    }

    @Test
    fun onStatusBarBoundsChanged_forwardsLetterboxAppearanceToCentralSurfaces() {
        changeSysBarAttrs(TEST_APPEARANCE, TEST_APPEARANCE_REGIONS, TEST_LETTERBOX_DETAILS)
        reset(centralSurfaces, lightBarController, statusBarStateController)

        sysBarAttrsListener.onStatusBarBoundsChanged()

        verify(centralSurfaces).setAppearance(TEST_LETTERBOX_APPEARANCE.appearance)
    }

    @Test
    fun onStatusBarBoundsChanged_previousCallEmptyLetterbox_doesNothing() {
        changeSysBarAttrs(TEST_APPEARANCE, TEST_APPEARANCE_REGIONS, arrayOf())
        reset(centralSurfaces, lightBarController, statusBarStateController)

        sysBarAttrsListener.onStatusBarBoundsChanged()

        verifyZeroInteractions(centralSurfaces, lightBarController, statusBarStateController)
    }

    private fun changeSysBarAttrs(@Appearance appearance: Int) {
        changeSysBarAttrs(appearance, arrayOf<LetterboxDetails>())
    }

    private fun changeSysBarAttrs(
        @Appearance appearance: Int,
        letterboxDetails: Array<LetterboxDetails>
    ) {
        changeSysBarAttrs(appearance, arrayOf(), letterboxDetails)
    }

    private fun changeSysBarAttrs(
        @Appearance appearance: Int,
        appearanceRegions: Array<AppearanceRegion>
    ) {
        changeSysBarAttrs(appearance, appearanceRegions, arrayOf())
    }

    private fun changeSysBarAttrs(
        @Appearance appearance: Int,
        appearanceRegions: Array<AppearanceRegion>,
        letterboxDetails: Array<LetterboxDetails>
    ) {
        sysBarAttrsListener.onSystemBarAttributesChanged(
            Display.DEFAULT_DISPLAY,
            appearance,
            appearanceRegions,
            /* navbarColorManagedByIme= */ false,
            WindowInsetsController.BEHAVIOR_DEFAULT,
            InsetsVisibilities(),
            "package name",
            letterboxDetails)
    }

    companion object {
        private const val TEST_APPEARANCE =
            APPEARANCE_LIGHT_STATUS_BARS or APPEARANCE_LIGHT_NAVIGATION_BARS
        private val TEST_APPEARANCE_REGION = AppearanceRegion(TEST_APPEARANCE, Rect(0, 0, 150, 300))
        private val TEST_APPEARANCE_REGIONS = arrayOf(TEST_APPEARANCE_REGION)
        private val TEST_LETTERBOX_DETAILS =
            arrayOf(
                LetterboxDetails(
                    /* letterboxInnerBounds= */ Rect(0, 0, 0, 0),
                    /* letterboxFullBounds= */ Rect(0, 0, 0, 0),
                    /* appAppearance= */ 0))
        private val TEST_LETTERBOX_APPEARANCE =
            LetterboxAppearance(/* appearance= */ APPEARANCE_LOW_PROFILE_BARS, arrayOf())
    }
}

private fun <T> anyObject(): T {
    return Mockito.anyObject<T>()
}
