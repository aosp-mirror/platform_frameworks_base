package com.android.systemui.communal.data.repository

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.os.UserHandle
import android.os.UserManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.RoboPilotTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.FakeLogBuffer
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.kotlinArgumentCaptor
import com.android.systemui.util.mockito.nullable
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.anyInt
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RoboPilotTest
@RunWith(AndroidJUnit4::class)
class CommunalWidgetRepositoryImplTest : SysuiTestCase() {
    @Mock private lateinit var appWidgetManager: AppWidgetManager

    @Mock private lateinit var appWidgetHost: AppWidgetHost

    @Mock private lateinit var broadcastDispatcher: BroadcastDispatcher

    @Mock private lateinit var packageManager: PackageManager

    @Mock private lateinit var userManager: UserManager

    @Mock private lateinit var userHandle: UserHandle

    @Mock private lateinit var userTracker: UserTracker

    @Mock private lateinit var featureFlags: FeatureFlags

    @Mock private lateinit var stopwatchProviderInfo: AppWidgetProviderInfo

    private lateinit var logBuffer: LogBuffer

    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        logBuffer = FakeLogBuffer.Factory.create()

        featureFlagEnabled(true)
        whenever(stopwatchProviderInfo.loadLabel(any())).thenReturn("Stopwatch")
        whenever(userTracker.userHandle).thenReturn(userHandle)
    }

    @Test
    fun broadcastReceiver_featureDisabled_doNotRegisterUserUnlockedBroadcastReceiver() =
        testScope.runTest {
            featureFlagEnabled(false)
            val repository = initCommunalWidgetRepository()
            collectLastValue(repository.stopwatchAppWidgetInfo)()
            verifyBroadcastReceiverNeverRegistered()
        }

    @Test
    fun broadcastReceiver_featureEnabledAndUserUnlocked_doNotRegisterBroadcastReceiver() =
        testScope.runTest {
            userUnlocked(true)
            val repository = initCommunalWidgetRepository()
            collectLastValue(repository.stopwatchAppWidgetInfo)()
            verifyBroadcastReceiverNeverRegistered()
        }

    @Test
    fun broadcastReceiver_featureEnabledAndUserLocked_registerBroadcastReceiver() =
        testScope.runTest {
            userUnlocked(false)
            val repository = initCommunalWidgetRepository()
            collectLastValue(repository.stopwatchAppWidgetInfo)()
            verifyBroadcastReceiverRegistered()
        }

    @Test
    fun broadcastReceiver_whenFlowFinishes_unregisterBroadcastReceiver() =
        testScope.runTest {
            userUnlocked(false)
            val repository = initCommunalWidgetRepository()

            val job = launch { repository.stopwatchAppWidgetInfo.collect() }
            runCurrent()
            val receiver = broadcastReceiverUpdate()

            job.cancel()
            runCurrent()

            Mockito.verify(broadcastDispatcher).unregisterReceiver(receiver)
        }

    @Test
    fun stopwatch_whenUserUnlocks_receiveProviderInfo() =
        testScope.runTest {
            userUnlocked(false)
            val repository = initCommunalWidgetRepository()
            val lastStopwatchProviderInfo = collectLastValue(repository.stopwatchAppWidgetInfo)
            assertThat(lastStopwatchProviderInfo()).isNull()

            userUnlocked(true)
            installedProviders(listOf(stopwatchProviderInfo))
            broadcastReceiverUpdate()

            assertThat(lastStopwatchProviderInfo()?.providerInfo).isEqualTo(stopwatchProviderInfo)
        }

    @Test
    fun stopwatch_userUnlockedButWidgetNotInstalled_noProviderInfo() =
        testScope.runTest {
            userUnlocked(true)
            installedProviders(listOf())

            val repository = initCommunalWidgetRepository()

            val lastStopwatchProviderInfo = collectLastValue(repository.stopwatchAppWidgetInfo)
            assertThat(lastStopwatchProviderInfo()).isNull()
        }

    @Test
    fun appWidgetId_providerInfoAvailable_allocateAppWidgetId() =
        testScope.runTest {
            userUnlocked(true)
            installedProviders(listOf(stopwatchProviderInfo))
            val repository = initCommunalWidgetRepository()
            collectLastValue(repository.stopwatchAppWidgetInfo)()
            Mockito.verify(appWidgetHost).allocateAppWidgetId()
        }

    @Test
    fun appWidgetId_userLockedAgainAfterProviderInfoAvailable_deleteAppWidgetId() =
        testScope.runTest {
            whenever(appWidgetHost.allocateAppWidgetId()).thenReturn(123456)
            userUnlocked(false)
            val repository = initCommunalWidgetRepository()
            val lastStopwatchProviderInfo = collectLastValue(repository.stopwatchAppWidgetInfo)
            assertThat(lastStopwatchProviderInfo()).isNull()

            // User unlocks
            userUnlocked(true)
            installedProviders(listOf(stopwatchProviderInfo))
            broadcastReceiverUpdate()

            // Verify app widget id allocated
            assertThat(lastStopwatchProviderInfo()?.appWidgetId).isEqualTo(123456)
            Mockito.verify(appWidgetHost).allocateAppWidgetId()
            Mockito.verify(appWidgetHost, Mockito.never()).deleteAppWidgetId(anyInt())

            // User locked again
            userUnlocked(false)
            broadcastReceiverUpdate()

            // Verify app widget id deleted
            assertThat(lastStopwatchProviderInfo()).isNull()
            Mockito.verify(appWidgetHost).deleteAppWidgetId(123456)
        }

    @Test
    fun appWidgetHost_userUnlocked_startListening() =
        testScope.runTest {
            userUnlocked(false)
            val repository = initCommunalWidgetRepository()
            collectLastValue(repository.stopwatchAppWidgetInfo)()
            Mockito.verify(appWidgetHost, Mockito.never()).startListening()

            userUnlocked(true)
            broadcastReceiverUpdate()
            collectLastValue(repository.stopwatchAppWidgetInfo)()

            Mockito.verify(appWidgetHost).startListening()
        }

    @Test
    fun appWidgetHost_userLockedAgain_stopListening() =
        testScope.runTest {
            userUnlocked(false)
            val repository = initCommunalWidgetRepository()
            collectLastValue(repository.stopwatchAppWidgetInfo)()

            userUnlocked(true)
            broadcastReceiverUpdate()
            collectLastValue(repository.stopwatchAppWidgetInfo)()

            Mockito.verify(appWidgetHost).startListening()
            Mockito.verify(appWidgetHost, Mockito.never()).stopListening()

            userUnlocked(false)
            broadcastReceiverUpdate()
            collectLastValue(repository.stopwatchAppWidgetInfo)()

            Mockito.verify(appWidgetHost).stopListening()
        }

    private fun initCommunalWidgetRepository(): CommunalWidgetRepositoryImpl {
        return CommunalWidgetRepositoryImpl(
            appWidgetManager,
            appWidgetHost,
            broadcastDispatcher,
            packageManager,
            userManager,
            userTracker,
            logBuffer,
            featureFlags,
        )
    }

    private fun verifyBroadcastReceiverRegistered() {
        Mockito.verify(broadcastDispatcher)
            .registerReceiver(
                any(),
                any(),
                nullable(),
                nullable(),
                anyInt(),
                nullable(),
            )
    }

    private fun verifyBroadcastReceiverNeverRegistered() {
        Mockito.verify(broadcastDispatcher, Mockito.never())
            .registerReceiver(
                any(),
                any(),
                nullable(),
                nullable(),
                anyInt(),
                nullable(),
            )
    }

    private fun broadcastReceiverUpdate(): BroadcastReceiver {
        val broadcastReceiverCaptor = kotlinArgumentCaptor<BroadcastReceiver>()
        Mockito.verify(broadcastDispatcher)
            .registerReceiver(
                broadcastReceiverCaptor.capture(),
                any(),
                nullable(),
                nullable(),
                anyInt(),
                nullable(),
            )
        broadcastReceiverCaptor.value.onReceive(null, null)
        return broadcastReceiverCaptor.value
    }

    private fun featureFlagEnabled(enabled: Boolean) {
        whenever(featureFlags.isEnabled(Flags.WIDGET_ON_KEYGUARD)).thenReturn(enabled)
    }

    private fun userUnlocked(userUnlocked: Boolean) {
        whenever(userManager.isUserUnlockingOrUnlocked(userHandle)).thenReturn(userUnlocked)
    }

    private fun installedProviders(providers: List<AppWidgetProviderInfo>) {
        whenever(appWidgetManager.installedProviders).thenReturn(providers)
    }
}
