package com.android.systemui.keyguard

import android.content.ComponentCallbacks2
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.keyguard.data.repository.FakeCommandQueue
import com.android.systemui.keyguard.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.shared.model.WakeSleepReason
import com.android.systemui.keyguard.shared.model.WakefulnessModel
import com.android.systemui.keyguard.shared.model.WakefulnessState
import com.android.systemui.utils.GlobalWindowManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidTestingRunner::class)
@SmallTest
class ResourceTrimmerTest : SysuiTestCase() {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val keyguardRepository = FakeKeyguardRepository()

    @Mock private lateinit var globalWindowManager: GlobalWindowManager
    @Mock private lateinit var featureFlags: FeatureFlags
    private lateinit var resourceTrimmer: ResourceTrimmer

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        keyguardRepository.setWakefulnessModel(
            WakefulnessModel(WakefulnessState.AWAKE, WakeSleepReason.OTHER, WakeSleepReason.OTHER)
        )
        keyguardRepository.setDozeAmount(0f)

        val interactor =
            KeyguardInteractor(
                keyguardRepository,
                FakeCommandQueue(),
                featureFlags,
                FakeKeyguardBouncerRepository()
            )
        resourceTrimmer =
            ResourceTrimmer(
                interactor,
                globalWindowManager,
                testScope.backgroundScope,
                testDispatcher
            )
        resourceTrimmer.start()
    }

    @Test
    fun noChange_noOutputChanges() =
        testScope.runTest {
            testScope.runCurrent()
            verifyZeroInteractions(globalWindowManager)
        }

    @Test
    fun dozeAodDisabled_sleep_trimsMemory() =
        testScope.runTest {
            keyguardRepository.setWakefulnessModel(
                WakefulnessModel(
                    WakefulnessState.ASLEEP,
                    WakeSleepReason.OTHER,
                    WakeSleepReason.OTHER
                )
            )
            testScope.runCurrent()
            verify(globalWindowManager, times(1))
                .trimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        }

    @Test
    fun dozeEnabled_sleepWithFullDozeAmount_trimsMemory() =
        testScope.runTest {
            keyguardRepository.setDreaming(true)
            keyguardRepository.setDozeAmount(1f)
            keyguardRepository.setWakefulnessModel(
                WakefulnessModel(
                    WakefulnessState.ASLEEP,
                    WakeSleepReason.OTHER,
                    WakeSleepReason.OTHER
                )
            )
            testScope.runCurrent()
            verify(globalWindowManager, times(1))
                .trimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        }

    @Test
    fun dozeEnabled_sleepWithoutFullDozeAmount_doesntTrimMemory() =
        testScope.runTest {
            keyguardRepository.setDreaming(true)
            keyguardRepository.setDozeAmount(0f)
            keyguardRepository.setWakefulnessModel(
                WakefulnessModel(
                    WakefulnessState.ASLEEP,
                    WakeSleepReason.OTHER,
                    WakeSleepReason.OTHER
                )
            )
            testScope.runCurrent()
            verifyZeroInteractions(globalWindowManager)
        }

    @Test
    fun aodEnabled_sleepWithFullDozeAmount_trimsMemoryOnce() {
        testScope.runTest {
            keyguardRepository.setDreaming(true)
            keyguardRepository.setDozeAmount(0f)
            keyguardRepository.setWakefulnessModel(
                WakefulnessModel(
                    WakefulnessState.ASLEEP,
                    WakeSleepReason.OTHER,
                    WakeSleepReason.OTHER
                )
            )

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
                .trimMemory(ComponentCallbacks2.TRIM_MEMORY_BACKGROUND)
        }
    }

    @Test
    fun aodEnabled_deviceWakesHalfWayThrough_doesNotTrimMemory() {
        testScope.runTest {
            keyguardRepository.setDreaming(true)
            keyguardRepository.setDozeAmount(0f)
            keyguardRepository.setWakefulnessModel(
                WakefulnessModel(
                    WakefulnessState.ASLEEP,
                    WakeSleepReason.OTHER,
                    WakeSleepReason.OTHER
                )
            )

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
}
