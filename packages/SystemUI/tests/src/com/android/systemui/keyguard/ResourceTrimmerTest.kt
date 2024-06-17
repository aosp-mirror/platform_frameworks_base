package com.android.systemui.keyguard

import android.content.ComponentCallbacks2
import android.graphics.HardwareRenderer
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.SetFlagsRule
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.utils.GlobalWindowManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidTestingRunner::class)
@SmallTest
class ResourceTrimmerTest : SysuiTestCase() {
    val kosmos = testKosmos()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val keyguardRepository = kosmos.fakeKeyguardRepository
    private val featureFlags = kosmos.fakeFeatureFlagsClassic
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val powerInteractor = kosmos.powerInteractor

    @Mock private lateinit var globalWindowManager: GlobalWindowManager
    private lateinit var resourceTrimmer: ResourceTrimmer

    @Rule @JvmField public val setFlagsRule = SetFlagsRule()

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        featureFlags.set(Flags.TRIM_FONT_CACHES_AT_UNLOCK, true)
        keyguardRepository.setDozeAmount(0f)
        keyguardRepository.setKeyguardGoingAway(false)

        val withDeps =
            KeyguardInteractorFactory.create(
                repository = keyguardRepository,
                featureFlags = featureFlags,
            )
        val keyguardInteractor = withDeps.keyguardInteractor
        resourceTrimmer =
            ResourceTrimmer(
                keyguardInteractor,
                powerInteractor,
                kosmos.keyguardTransitionInteractor,
                globalWindowManager,
                testScope.backgroundScope,
                testDispatcher,
                featureFlags
            )
        resourceTrimmer.start()
    }

    @Test
    @EnableFlags(com.android.systemui.Flags.FLAG_TRIM_RESOURCES_WITH_BACKGROUND_TRIM_AT_LOCK)
    fun noChange_noOutputChanges() =
        testScope.runTest {
            testScope.runCurrent()
            verifyZeroInteractions(globalWindowManager)
        }

    @Test
    @EnableFlags(com.android.systemui.Flags.FLAG_TRIM_RESOURCES_WITH_BACKGROUND_TRIM_AT_LOCK)
    fun dozeAodDisabled_sleep_trimsMemory() =
        testScope.runTest {
            powerInteractor.setAsleepForTest()
            testScope.runCurrent()
            verify(globalWindowManager, times(1))
                .trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            verify(globalWindowManager, times(1)).trimCaches(HardwareRenderer.CACHE_TRIM_ALL)
        }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_TRIM_RESOURCES_WITH_BACKGROUND_TRIM_AT_LOCK)
    fun dozeAodDisabled_flagDisabled_sleep_doesntTrimMemory() =
        testScope.runTest {
            powerInteractor.setAsleepForTest()
            testScope.runCurrent()
            verifyZeroInteractions(globalWindowManager)
        }

    @Test
    @DisableFlags(com.android.systemui.Flags.FLAG_TRIM_RESOURCES_WITH_BACKGROUND_TRIM_AT_LOCK)
    fun dozeEnabled_flagDisabled_sleepWithFullDozeAmount_doesntTrimMemory() =
        testScope.runTest {
            keyguardRepository.setDreaming(true)
            keyguardRepository.setDozeAmount(1f)
            powerInteractor.setAsleepForTest()
            testScope.runCurrent()
            verifyZeroInteractions(globalWindowManager)
        }

    @Test
    @EnableFlags(com.android.systemui.Flags.FLAG_TRIM_RESOURCES_WITH_BACKGROUND_TRIM_AT_LOCK)
    fun dozeEnabled_sleepWithFullDozeAmount_trimsMemory() =
        testScope.runTest {
            keyguardRepository.setDreaming(true)
            keyguardRepository.setDozeAmount(1f)
            powerInteractor.setAsleepForTest()
            testScope.runCurrent()
            verify(globalWindowManager, times(1))
                .trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            verify(globalWindowManager, times(1)).trimCaches(HardwareRenderer.CACHE_TRIM_ALL)
        }

    @Test
    @EnableFlags(com.android.systemui.Flags.FLAG_TRIM_RESOURCES_WITH_BACKGROUND_TRIM_AT_LOCK)
    fun dozeEnabled_sleepWithoutFullDozeAmount_doesntTrimMemory() =
        testScope.runTest {
            keyguardRepository.setDreaming(true)
            keyguardRepository.setDozeAmount(0f)
            powerInteractor.setAsleepForTest()
            testScope.runCurrent()
            verifyZeroInteractions(globalWindowManager)
        }

    @Test
    @EnableFlags(com.android.systemui.Flags.FLAG_TRIM_RESOURCES_WITH_BACKGROUND_TRIM_AT_LOCK)
    fun aodEnabled_sleepWithFullDozeAmount_trimsMemoryOnce() {
        testScope.runTest {
            keyguardRepository.setDreaming(true)
            keyguardRepository.setDozeAmount(0f)
            powerInteractor.setAsleepForTest()

            testScope.runCurrent()
            verifyZeroInteractions(globalWindowManager)

            generateSequence(0f) { it + 0.1f }
                .takeWhile { it < 1f }
                .forEach {
                    keyguardRepository.setDozeAmount(it)
                    testScope.runCurrent()
                }
            verifyZeroInteractions(globalWindowManager)

            keyguardRepository.setDozeAmount(1f)
            testScope.runCurrent()
            verify(globalWindowManager, times(1))
                .trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            verify(globalWindowManager, times(1)).trimCaches(HardwareRenderer.CACHE_TRIM_ALL)
        }
    }

    @Test
    @EnableFlags(com.android.systemui.Flags.FLAG_TRIM_RESOURCES_WITH_BACKGROUND_TRIM_AT_LOCK)
    fun aodEnabled_deviceWakesHalfWayThrough_doesNotTrimMemory() {
        testScope.runTest {
            keyguardRepository.setDreaming(true)
            keyguardRepository.setDozeAmount(0f)
            powerInteractor.setAsleepForTest()

            testScope.runCurrent()
            verifyZeroInteractions(globalWindowManager)

            generateSequence(0f) { it + 0.1f }
                .takeWhile { it < 0.8f }
                .forEach {
                    keyguardRepository.setDozeAmount(it)
                    testScope.runCurrent()
                }
            verifyZeroInteractions(globalWindowManager)

            generateSequence(0.8f) { it - 0.1f }
                .takeWhile { it >= 0f }
                .forEach {
                    keyguardRepository.setDozeAmount(it)
                    testScope.runCurrent()
                }

            keyguardRepository.setDozeAmount(0f)
            testScope.runCurrent()
            verifyZeroInteractions(globalWindowManager)
        }
    }

    @Test
    @EnableFlags(com.android.systemui.Flags.FLAG_TRIM_RESOURCES_WITH_BACKGROUND_TRIM_AT_LOCK)
    fun keyguardTransitionsToGone_trimsFontCache() =
        testScope.runTest {
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope
            )
            verify(globalWindowManager, times(1))
                .trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            verify(globalWindowManager, times(1)).trimCaches(HardwareRenderer.CACHE_TRIM_FONT)
            verifyNoMoreInteractions(globalWindowManager)
        }

    @Test
    @EnableFlags(com.android.systemui.Flags.FLAG_TRIM_RESOURCES_WITH_BACKGROUND_TRIM_AT_LOCK)
    fun keyguardTransitionsToGone_flagDisabled_doesNotTrimFontCache() =
        testScope.runTest {
            featureFlags.set(Flags.TRIM_FONT_CACHES_AT_UNLOCK, false)
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope
            )
            // Memory hidden should still be called.
            verify(globalWindowManager, times(1))
                .trimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
            verify(globalWindowManager, times(0)).trimCaches(any())
        }
}
