package com.android.systemui.bouncer.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.SystemClock
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardBouncerRepositoryTest : SysuiTestCase() {

    @Mock private lateinit var systemClock: SystemClock
    @Mock private lateinit var bouncerLogger: TableLogBuffer

    private val utils = SceneTestUtils(this)
    private val testScope = utils.testScope

    lateinit var underTest: KeyguardBouncerRepository

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        underTest =
            KeyguardBouncerRepositoryImpl(
                systemClock,
                testScope.backgroundScope,
                bouncerLogger,
            )
    }

    @Test
    fun changingFlowValueTriggersLogging() =
        testScope.runTest {
            underTest.setPrimaryShow(true)
            Mockito.verify(bouncerLogger)
                .logChange(eq(""), eq("PrimaryBouncerShow"), value = eq(false), any())
        }
}
