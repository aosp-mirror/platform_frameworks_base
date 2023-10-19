package com.android.systemui.bouncer.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.ViewMediatorCallback
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.SystemClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScope
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class KeyguardBouncerRepositoryTest : SysuiTestCase() {

    @Mock private lateinit var systemClock: SystemClock
    @Mock private lateinit var viewMediatorCallback: ViewMediatorCallback
    @Mock private lateinit var bouncerLogger: TableLogBuffer
    lateinit var underTest: KeyguardBouncerRepository

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        val testCoroutineScope = TestCoroutineScope()
        underTest =
            KeyguardBouncerRepositoryImpl(
                systemClock,
                testCoroutineScope,
                bouncerLogger,
            )
    }

    @Test
    fun changingFlowValueTriggersLogging() = runBlocking {
        underTest.setPrimaryShow(true)
        Mockito.verify(bouncerLogger)
            .logChange(eq(""), eq("PrimaryBouncerShow"), value = eq(false), any())
    }
}
