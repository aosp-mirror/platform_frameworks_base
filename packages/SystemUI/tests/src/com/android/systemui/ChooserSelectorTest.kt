package com.android.systemui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flag
import com.android.systemui.flags.FlagListenable
import com.android.systemui.flags.Flags
import com.android.systemui.flags.UnreleasedFlag
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.kotlinArgumentCaptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestCoroutineDispatcher
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidTestingRunner::class)
@SmallTest
class ChooserSelectorTest : SysuiTestCase() {

    private val flagListener = kotlinArgumentCaptor<FlagListenable.Listener>()

    private val testDispatcher = TestCoroutineDispatcher()
    private val testScope = CoroutineScope(testDispatcher)

    private lateinit var chooserSelector: ChooserSelector

    @Mock private lateinit var mockContext: Context
    @Mock private lateinit var mockPackageManager: PackageManager
    @Mock private lateinit var mockResources: Resources
    @Mock private lateinit var mockFeatureFlags: FeatureFlags

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        `when`(mockContext.packageManager).thenReturn(mockPackageManager)
        `when`(mockContext.resources).thenReturn(mockResources)
        `when`(mockResources.getString(anyInt())).thenReturn(
                ComponentName("TestPackage", "TestClass").flattenToString())

        chooserSelector = ChooserSelector(mockContext, mockFeatureFlags, testScope, testDispatcher)
    }

    @After
    fun tearDown() {
        testDispatcher.cleanupTestCoroutines()
    }

    @Test
    fun initialize_registersFlagListenerUntilScopeCancelled() {
        // Arrange

        // Act
        chooserSelector.start()

        // Assert
        verify(mockFeatureFlags).addListener(
                eq<Flag<*>>(Flags.CHOOSER_UNBUNDLED), flagListener.capture())
        verify(mockFeatureFlags, never()).removeListener(any())

        // Act
        testScope.cancel()

        // Assert
        verify(mockFeatureFlags).removeListener(eq(flagListener.value))
    }

    @Test
    fun initialize_enablesUnbundledChooser_whenFlagEnabled() {
        // Arrange
        `when`(mockFeatureFlags.isEnabled(any<UnreleasedFlag>())).thenReturn(true)

        // Act
        chooserSelector.start()

        // Assert
        verify(mockPackageManager).setComponentEnabledSetting(
                eq(ComponentName("TestPackage", "TestClass")),
                eq(PackageManager.COMPONENT_ENABLED_STATE_ENABLED),
                anyInt())
    }

    @Test
    fun initialize_disablesUnbundledChooser_whenFlagDisabled() {
        // Arrange
        `when`(mockFeatureFlags.isEnabled(any<UnreleasedFlag>())).thenReturn(false)

        // Act
        chooserSelector.start()

        // Assert
        verify(mockPackageManager).setComponentEnabledSetting(
                eq(ComponentName("TestPackage", "TestClass")),
                eq(PackageManager.COMPONENT_ENABLED_STATE_DISABLED),
                anyInt())
    }

    @Test
    fun enablesUnbundledChooser_whenFlagBecomesEnabled() {
        // Arrange
        `when`(mockFeatureFlags.isEnabled(any<UnreleasedFlag>())).thenReturn(false)
        chooserSelector.start()
        verify(mockFeatureFlags).addListener(
                eq<Flag<*>>(Flags.CHOOSER_UNBUNDLED), flagListener.capture())
        verify(mockPackageManager, never()).setComponentEnabledSetting(
                any(), eq(PackageManager.COMPONENT_ENABLED_STATE_ENABLED), anyInt())

        // Act
        `when`(mockFeatureFlags.isEnabled(any<UnreleasedFlag>())).thenReturn(true)
        flagListener.value.onFlagChanged(TestFlagEvent(Flags.CHOOSER_UNBUNDLED.id))

        // Assert
        verify(mockPackageManager).setComponentEnabledSetting(
                eq(ComponentName("TestPackage", "TestClass")),
                eq(PackageManager.COMPONENT_ENABLED_STATE_ENABLED),
                anyInt())
    }

    @Test
    fun disablesUnbundledChooser_whenFlagBecomesDisabled() {
        // Arrange
        `when`(mockFeatureFlags.isEnabled(any<UnreleasedFlag>())).thenReturn(true)
        chooserSelector.start()
        verify(mockFeatureFlags).addListener(
                eq<Flag<*>>(Flags.CHOOSER_UNBUNDLED), flagListener.capture())
        verify(mockPackageManager, never()).setComponentEnabledSetting(
                any(), eq(PackageManager.COMPONENT_ENABLED_STATE_DISABLED), anyInt())

        // Act
        `when`(mockFeatureFlags.isEnabled(any<UnreleasedFlag>())).thenReturn(false)
        flagListener.value.onFlagChanged(TestFlagEvent(Flags.CHOOSER_UNBUNDLED.id))

        // Assert
        verify(mockPackageManager).setComponentEnabledSetting(
                eq(ComponentName("TestPackage", "TestClass")),
                eq(PackageManager.COMPONENT_ENABLED_STATE_DISABLED),
                anyInt())
    }

    @Test
    fun doesNothing_whenAnotherFlagChanges() {
        // Arrange
        `when`(mockFeatureFlags.isEnabled(any<UnreleasedFlag>())).thenReturn(false)
        chooserSelector.start()
        verify(mockFeatureFlags).addListener(
                eq<Flag<*>>(Flags.CHOOSER_UNBUNDLED), flagListener.capture())
        clearInvocations(mockPackageManager)

        // Act
        `when`(mockFeatureFlags.isEnabled(any<UnreleasedFlag>())).thenReturn(false)
        flagListener.value.onFlagChanged(TestFlagEvent(Flags.CHOOSER_UNBUNDLED.id + 1))

        // Assert
        verifyZeroInteractions(mockPackageManager)
    }

    private class TestFlagEvent(override val flagId: Int) : FlagListenable.FlagEvent {
        override fun requestNoRestart() {}
    }
}
