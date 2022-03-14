package com.android.systemui.statusbar.phone

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.WindowInsets
import android.view.WindowManagerPolicyConstants
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.navigationbar.NavigationModeController
import com.android.systemui.navigationbar.NavigationModeController.ModeChangedListener
import com.android.systemui.recents.OverviewProxyService
import com.android.systemui.recents.OverviewProxyService.OverviewProxyListener
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.RETURNS_DEEP_STUBS
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.eq
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations
import java.util.function.Consumer
import org.mockito.Mockito.`when` as whenever

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class NotificationQSContainerControllerTest : SysuiTestCase() {

    companion object {
        const val STABLE_INSET_BOTTOM = 100
        const val CUTOUT_HEIGHT = 50
        const val GESTURES_NAVIGATION = WindowManagerPolicyConstants.NAV_BAR_MODE_GESTURAL
        const val BUTTONS_NAVIGATION = WindowManagerPolicyConstants.NAV_BAR_MODE_3BUTTON
        const val NOTIFICATIONS_MARGIN = 50
    }

    @Mock
    private lateinit var navigationModeController: NavigationModeController
    @Mock
    private lateinit var overviewProxyService: OverviewProxyService
    @Mock
    private lateinit var notificationsQSContainer: NotificationsQuickSettingsContainer
    @Captor
    lateinit var navigationModeCaptor: ArgumentCaptor<ModeChangedListener>
    @Captor
    lateinit var taskbarVisibilityCaptor: ArgumentCaptor<OverviewProxyListener>
    @Captor
    lateinit var windowInsetsCallbackCaptor: ArgumentCaptor<Consumer<WindowInsets>>

    private lateinit var notificationsQSContainerController: NotificationsQSContainerController
    private lateinit var navigationModeCallback: ModeChangedListener
    private lateinit var taskbarVisibilityCallback: OverviewProxyListener
    private lateinit var windowInsetsCallback: Consumer<WindowInsets>

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        notificationsQSContainerController = NotificationsQSContainerController(
                notificationsQSContainer,
                navigationModeController,
                overviewProxyService
        )
        whenever(notificationsQSContainer.defaultNotificationsMarginBottom)
                .thenReturn(NOTIFICATIONS_MARGIN)
        whenever(navigationModeController.addListener(navigationModeCaptor.capture()))
                .thenReturn(GESTURES_NAVIGATION)
        doNothing().`when`(overviewProxyService).addCallback(taskbarVisibilityCaptor.capture())
        doNothing().`when`(notificationsQSContainer)
                .setInsetsChangedListener(windowInsetsCallbackCaptor.capture())

        notificationsQSContainerController.init()
        notificationsQSContainerController.onViewAttached()

        navigationModeCallback = navigationModeCaptor.value
        taskbarVisibilityCallback = taskbarVisibilityCaptor.value
        windowInsetsCallback = windowInsetsCallbackCaptor.value
    }

    @Test
    fun testTaskbarVisibleInSplitShade() {
        notificationsQSContainerController.splitShadeEnabled = true
        given(taskbarVisible = true,
                navigationMode = GESTURES_NAVIGATION,
                insets = windowInsets().withStableBottom())
        then(expectedContainerPadding = 0, // taskbar should disappear when shade is expanded
                expectedNotificationsMargin = NOTIFICATIONS_MARGIN)

        given(taskbarVisible = true,
                navigationMode = BUTTONS_NAVIGATION,
                insets = windowInsets().withStableBottom())
        then(expectedContainerPadding = STABLE_INSET_BOTTOM,
                expectedNotificationsMargin = NOTIFICATIONS_MARGIN)
    }

    @Test
    fun testTaskbarNotVisibleInSplitShade() {
        // when taskbar is not visible, it means we're on the home screen
        notificationsQSContainerController.splitShadeEnabled = true
        given(taskbarVisible = false,
                navigationMode = GESTURES_NAVIGATION,
                insets = windowInsets().withStableBottom())
        then(expectedContainerPadding = 0)

        given(taskbarVisible = false,
                navigationMode = BUTTONS_NAVIGATION,
                insets = windowInsets().withStableBottom())
        then(expectedContainerPadding = 0, // qs goes full height as it's not obscuring nav buttons
                expectedNotificationsMargin = STABLE_INSET_BOTTOM + NOTIFICATIONS_MARGIN)
    }

    @Test
    fun testTaskbarNotVisibleInSplitShadeWithCutout() {
        notificationsQSContainerController.splitShadeEnabled = true
        given(taskbarVisible = false,
                navigationMode = GESTURES_NAVIGATION,
                insets = windowInsets().withCutout())
        then(expectedContainerPadding = CUTOUT_HEIGHT)

        given(taskbarVisible = false,
                navigationMode = BUTTONS_NAVIGATION,
                insets = windowInsets().withCutout().withStableBottom())
        then(expectedContainerPadding = 0,
                expectedNotificationsMargin = STABLE_INSET_BOTTOM + NOTIFICATIONS_MARGIN)
    }

    @Test
    fun testTaskbarVisibleInSinglePaneShade() {
        notificationsQSContainerController.splitShadeEnabled = false
        given(taskbarVisible = true,
                navigationMode = GESTURES_NAVIGATION,
                insets = windowInsets().withStableBottom())
        then(expectedContainerPadding = 0)

        given(taskbarVisible = true,
                navigationMode = BUTTONS_NAVIGATION,
                insets = windowInsets().withStableBottom())
        then(expectedContainerPadding = STABLE_INSET_BOTTOM)
    }

    @Test
    fun testTaskbarNotVisibleInSinglePaneShade() {
        notificationsQSContainerController.splitShadeEnabled = false
        given(taskbarVisible = false,
                navigationMode = GESTURES_NAVIGATION,
                insets = emptyInsets())
        then(expectedContainerPadding = 0)

        given(taskbarVisible = false,
                navigationMode = GESTURES_NAVIGATION,
                insets = windowInsets().withCutout().withStableBottom())
        then(expectedContainerPadding = CUTOUT_HEIGHT)

        given(taskbarVisible = false,
                navigationMode = BUTTONS_NAVIGATION,
                insets = windowInsets().withStableBottom())
        then(expectedContainerPadding = 0,
                expectedQsPadding = STABLE_INSET_BOTTOM)
    }

    @Test
    fun testCustomizingInSinglePaneShade() {
        notificationsQSContainerController.splitShadeEnabled = false
        notificationsQSContainerController.setCustomizerShowing(true)
        // always sets spacings to 0
        given(taskbarVisible = false,
                navigationMode = GESTURES_NAVIGATION,
                insets = windowInsets().withStableBottom())
        then(expectedContainerPadding = 0,
                expectedNotificationsMargin = 0)

        given(taskbarVisible = false,
                navigationMode = BUTTONS_NAVIGATION,
                insets = emptyInsets())
        then(expectedContainerPadding = 0,
                expectedNotificationsMargin = 0)
    }

    @Test
    fun testDetailShowingInSinglePaneShade() {
        notificationsQSContainerController.splitShadeEnabled = false
        notificationsQSContainerController.setDetailShowing(true)
        // always sets spacings to 0
        given(taskbarVisible = false,
                navigationMode = GESTURES_NAVIGATION,
                insets = windowInsets().withStableBottom())
        then(expectedContainerPadding = 0,
                expectedNotificationsMargin = 0)

        given(taskbarVisible = false,
                navigationMode = BUTTONS_NAVIGATION,
                insets = emptyInsets())
        then(expectedContainerPadding = 0,
                expectedNotificationsMargin = 0)
    }

    @Test
    fun testDetailShowingInSplitShade() {
        notificationsQSContainerController.splitShadeEnabled = true
        given(taskbarVisible = false,
                navigationMode = GESTURES_NAVIGATION,
                insets = windowInsets().withStableBottom())
        then(expectedContainerPadding = 0)

        notificationsQSContainerController.setDetailShowing(true)
        // should not influence spacing
        given(taskbarVisible = false,
                navigationMode = BUTTONS_NAVIGATION,
                insets = emptyInsets())
        then(expectedContainerPadding = 0)
    }

    private fun given(
        taskbarVisible: Boolean,
        navigationMode: Int,
        insets: WindowInsets
    ) {
        Mockito.clearInvocations(notificationsQSContainer)
        taskbarVisibilityCallback.onTaskbarStatusUpdated(taskbarVisible, false)
        navigationModeCallback.onNavigationModeChanged(navigationMode)
        windowInsetsCallback.accept(insets)
    }

    fun then(
        expectedContainerPadding: Int,
        expectedNotificationsMargin: Int = NOTIFICATIONS_MARGIN,
        expectedQsPadding: Int = 0
    ) {
        verify(notificationsQSContainer)
                .setPadding(anyInt(), anyInt(), anyInt(), eq(expectedContainerPadding))
        verify(notificationsQSContainer).setNotificationsMarginBottom(expectedNotificationsMargin)
        verify(notificationsQSContainer).setQSScrollPaddingBottom(expectedQsPadding)
        Mockito.clearInvocations(notificationsQSContainer)
    }

    private fun windowInsets() = mock(WindowInsets::class.java, RETURNS_DEEP_STUBS)

    private fun emptyInsets() = mock(WindowInsets::class.java)

    private fun WindowInsets.withCutout(): WindowInsets {
        whenever(displayCutout.safeInsetBottom).thenReturn(CUTOUT_HEIGHT)
        return this
    }

    private fun WindowInsets.withStableBottom(): WindowInsets {
        whenever(stableInsetBottom).thenReturn(STABLE_INSET_BOTTOM)
        return this
    }
}