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
import com.android.systemui.SysuiTestCase
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.communal.data.model.CommunalWidgetMetadata
import com.android.systemui.communal.shared.model.CommunalContentSize
import com.android.systemui.communal.shared.model.CommunalWidgetContentModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.Flags
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.FakeLogBuffer
import com.android.systemui.res.R
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
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalWidgetRepositoryImplTest : SysuiTestCase() {
    @Mock private lateinit var appWidgetManager: AppWidgetManager

    @Mock private lateinit var appWidgetHost: AppWidgetHost

    @Mock private lateinit var broadcastDispatcher: BroadcastDispatcher

    @Mock private lateinit var packageManager: PackageManager

    @Mock private lateinit var userManager: UserManager

    @Mock private lateinit var userHandle: UserHandle

    @Mock private lateinit var userTracker: UserTracker

    @Mock private lateinit var featureFlags: FeatureFlagsClassic

    @Mock private lateinit var stopwatchProviderInfo: AppWidgetProviderInfo

    @Mock private lateinit var providerInfoA: AppWidgetProviderInfo

    @Mock private lateinit var providerInfoB: AppWidgetProviderInfo

    @Mock private lateinit var providerInfoC: AppWidgetProviderInfo

    private lateinit var communalRepository: FakeCommunalRepository

    private lateinit var logBuffer: LogBuffer

    private val testDispatcher = StandardTestDispatcher()

    private val testScope = TestScope(testDispatcher)

    private val fakeAllowlist =
        listOf(
            "com.android.fake/WidgetProviderA",
            "com.android.fake/WidgetProviderB",
            "com.android.fake/WidgetProviderC",
        )

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        logBuffer = FakeLogBuffer.Factory.create()
        communalRepository = FakeCommunalRepository()

        communalEnabled(true)
        widgetOnKeyguardEnabled(true)
        setAppWidgetIds(emptyList())

        overrideResource(R.array.config_communalWidgetAllowlist, fakeAllowlist.toTypedArray())

        whenever(stopwatchProviderInfo.loadLabel(any())).thenReturn("Stopwatch")
        whenever(userTracker.userHandle).thenReturn(userHandle)
    }

    @Test
    fun broadcastReceiver_communalDisabled_doNotRegisterUserUnlockedBroadcastReceiver() =
        testScope.runTest {
            communalEnabled(false)
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

            verify(broadcastDispatcher).unregisterReceiver(receiver)
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
            verify(appWidgetHost).allocateAppWidgetId()
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
            verify(appWidgetHost).allocateAppWidgetId()
            verify(appWidgetHost, Mockito.never()).deleteAppWidgetId(anyInt())

            // User locked again
            userUnlocked(false)
            broadcastReceiverUpdate()

            // Verify app widget id deleted
            assertThat(lastStopwatchProviderInfo()).isNull()
            verify(appWidgetHost).deleteAppWidgetId(123456)
        }

    @Test
    fun appWidgetHost_userUnlocked_startListening() =
        testScope.runTest {
            userUnlocked(false)
            val repository = initCommunalWidgetRepository()
            collectLastValue(repository.stopwatchAppWidgetInfo)()
            verify(appWidgetHost, Mockito.never()).startListening()

            userUnlocked(true)
            broadcastReceiverUpdate()
            collectLastValue(repository.stopwatchAppWidgetInfo)()

            verify(appWidgetHost).startListening()
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

            verify(appWidgetHost).startListening()
            verify(appWidgetHost, Mockito.never()).stopListening()

            userUnlocked(false)
            broadcastReceiverUpdate()
            collectLastValue(repository.stopwatchAppWidgetInfo)()

            verify(appWidgetHost).stopListening()
        }

    @Test
    fun getCommunalWidgetAllowList_onInit() {
        testScope.runTest {
            val repository = initCommunalWidgetRepository()
            val communalWidgetAllowlist = repository.communalWidgetAllowlist
            assertThat(
                    listOf(
                        CommunalWidgetMetadata(
                            componentName = fakeAllowlist[0],
                            priority = 3,
                            sizes = listOf(CommunalContentSize.HALF),
                        ),
                        CommunalWidgetMetadata(
                            componentName = fakeAllowlist[1],
                            priority = 2,
                            sizes = listOf(CommunalContentSize.HALF),
                        ),
                        CommunalWidgetMetadata(
                            componentName = fakeAllowlist[2],
                            priority = 1,
                            sizes = listOf(CommunalContentSize.HALF),
                        ),
                    )
                )
                .containsExactly(*communalWidgetAllowlist.toTypedArray())
        }
    }

    // This behavior is temporary before the local database is set up.
    @Test
    fun communalWidgets_withPreviouslyBoundWidgets_removeEachBinding() =
        testScope.runTest {
            whenever(appWidgetHost.allocateAppWidgetId()).thenReturn(1, 2, 3)
            setAppWidgetIds(listOf(1, 2, 3))
            whenever(appWidgetManager.getAppWidgetInfo(anyInt())).thenReturn(providerInfoA)
            userUnlocked(true)

            val repository = initCommunalWidgetRepository()

            collectLastValue(repository.communalWidgets)()

            verify(appWidgetHost).deleteAppWidgetId(1)
            verify(appWidgetHost).deleteAppWidgetId(2)
            verify(appWidgetHost).deleteAppWidgetId(3)
        }

    @Test
    fun communalWidgets_allowlistNotEmpty_bindEachWidgetFromTheAllowlist() =
        testScope.runTest {
            whenever(appWidgetHost.allocateAppWidgetId()).thenReturn(0, 1, 2)
            userUnlocked(true)

            whenever(appWidgetManager.getAppWidgetInfo(0)).thenReturn(providerInfoA)
            whenever(appWidgetManager.getAppWidgetInfo(1)).thenReturn(providerInfoB)
            whenever(appWidgetManager.getAppWidgetInfo(2)).thenReturn(providerInfoC)

            val repository = initCommunalWidgetRepository()

            val inventory by collectLastValue(repository.communalWidgets)

            assertThat(
                    listOf(
                        CommunalWidgetContentModel(
                            appWidgetId = 0,
                            providerInfo = providerInfoA,
                            priority = 3,
                        ),
                        CommunalWidgetContentModel(
                            appWidgetId = 1,
                            providerInfo = providerInfoB,
                            priority = 2,
                        ),
                        CommunalWidgetContentModel(
                            appWidgetId = 2,
                            providerInfo = providerInfoC,
                            priority = 1,
                        ),
                    )
                )
                .containsExactly(*inventory!!.toTypedArray())
        }

    private fun initCommunalWidgetRepository(): CommunalWidgetRepositoryImpl {
        return CommunalWidgetRepositoryImpl(
            context,
            appWidgetManager,
            appWidgetHost,
            broadcastDispatcher,
            communalRepository,
            packageManager,
            userManager,
            userTracker,
            logBuffer,
            featureFlags,
        )
    }

    private fun verifyBroadcastReceiverRegistered() {
        verify(broadcastDispatcher)
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
        verify(broadcastDispatcher, Mockito.never())
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
        verify(broadcastDispatcher)
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

    private fun communalEnabled(enabled: Boolean) {
        communalRepository.setIsCommunalEnabled(enabled)
    }

    private fun widgetOnKeyguardEnabled(enabled: Boolean) {
        whenever(featureFlags.isEnabled(Flags.WIDGET_ON_KEYGUARD)).thenReturn(enabled)
    }

    private fun userUnlocked(userUnlocked: Boolean) {
        whenever(userManager.isUserUnlockingOrUnlocked(userHandle)).thenReturn(userUnlocked)
    }

    private fun installedProviders(providers: List<AppWidgetProviderInfo>) {
        whenever(appWidgetManager.installedProviders).thenReturn(providers)
    }

    private fun setAppWidgetIds(ids: List<Int>) {
        whenever(appWidgetHost.appWidgetIds).thenReturn(ids.toIntArray())
    }
}
