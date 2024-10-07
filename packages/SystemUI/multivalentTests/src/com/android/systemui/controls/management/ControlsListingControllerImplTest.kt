/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.controls.management

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.UserHandle
import android.service.controls.ControlsProviderService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.settingslib.applications.ServiceListing
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.ActivityTaskManagerProxy
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argThat
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatcher
import org.mockito.Mock
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.mock

@SmallTest
@RunWith(AndroidJUnit4::class)
class ControlsListingControllerImplTest : SysuiTestCase() {

    companion object {
        private const val FLAGS =
            PackageManager.MATCH_DIRECT_BOOT_AWARE.toLong() or
                PackageManager.MATCH_DIRECT_BOOT_UNAWARE.toLong()
    }

    @Mock private lateinit var mockSL: ServiceListing
    @Mock private lateinit var mockCallback: ControlsListingController.ControlsListingCallback
    @Mock private lateinit var mockCallbackOther: ControlsListingController.ControlsListingCallback
    @Mock(stubOnly = true) private lateinit var userTracker: UserTracker
    @Mock(stubOnly = true) private lateinit var dumpManager: DumpManager
    @Mock private lateinit var packageManager: PackageManager
    @Mock private lateinit var featureFlags: FeatureFlags
    @Mock private lateinit var activityTaskManagerProxy: ActivityTaskManagerProxy

    private var componentName = ComponentName("pkg", "class1")
    private var activityName = ComponentName("pkg", "activity")

    private val executor = FakeExecutor(FakeSystemClock())

    private lateinit var controller: ControlsListingControllerImpl

    private var serviceListingCallbackCaptor =
        ArgumentCaptor.forClass(ServiceListing.Callback::class.java)

    private val user = mContext.userId
    private val otherUser = user + 1

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        `when`(userTracker.userId).thenReturn(user)
        `when`(userTracker.userHandle).thenReturn(UserHandle.of(user))
        `when`(userTracker.userContext).thenReturn(context)
        // Return disabled by default
        `when`(packageManager.getComponentEnabledSetting(any()))
            .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        `when`(activityTaskManagerProxy.supportsMultiWindow(any())).thenReturn(true)
        mContext.setMockPackageManager(packageManager)

        mContext.orCreateTestableResources.addOverride(
            R.array.config_controlsPreferredPackages,
            arrayOf(componentName.packageName)
        )

        val wrapper =
            object : ContextWrapper(mContext) {
                override fun createContextAsUser(user: UserHandle, flags: Int): Context {
                    return baseContext
                }
            }

        controller =
            ControlsListingControllerImpl(
                wrapper,
                executor,
                { mockSL },
                userTracker,
                activityTaskManagerProxy,
                dumpManager,
                featureFlags
            )
        verify(mockSL).addCallback(capture(serviceListingCallbackCaptor))
    }

    @After
    fun tearDown() {
        executor.advanceClockToLast()
        executor.runAllReady()
    }

    @Test
    fun testInitialStateListening() {
        verify(mockSL).setListening(true)
        verify(mockSL).reload()
    }

    @Test
    fun testImmediateListingReload_doesNotCrash() {
        val exec = Executor { it.run() }
        val mockServiceListing = mock(ServiceListing::class.java)
        var callback: ServiceListing.Callback? = null
        `when`(mockServiceListing.addCallback(any<ServiceListing.Callback>())).then {
            callback = it.getArgument(0)
            Unit
        }
        `when`(mockServiceListing.reload()).then {
            callback?.onServicesReloaded(listOf(ServiceInfo(componentName)))
        }
        ControlsListingControllerImpl(
            mContext,
            exec,
            { mockServiceListing },
            userTracker,
            activityTaskManagerProxy,
            dumpManager,
            featureFlags
        )
    }

    @Test
    fun testStartsOnUser() {
        assertEquals(user, controller.currentUserId)
    }

    @Test
    fun testCallbackCalledWhenAdded() {
        controller.addCallback(mockCallback)
        executor.runAllReady()
        verify(mockCallback).onServicesUpdated(any())
        reset(mockCallback)

        controller.addCallback(mockCallbackOther)
        executor.runAllReady()
        verify(mockCallbackOther).onServicesUpdated(any())
        verify(mockCallback, never()).onServicesUpdated(any())
    }

    @Test
    fun testCallbackGetsList() {
        val list = listOf(ServiceInfo(componentName))
        controller.addCallback(mockCallback)
        controller.addCallback(mockCallbackOther)

        @Suppress("unchecked_cast")
        val captor: ArgumentCaptor<List<ControlsServiceInfo>> =
            ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<ControlsServiceInfo>>

        executor.runAllReady()
        reset(mockCallback)
        reset(mockCallbackOther)

        serviceListingCallbackCaptor.value.onServicesReloaded(list)

        executor.runAllReady()
        verify(mockCallback).onServicesUpdated(capture(captor))
        assertEquals(1, captor.value.size)
        assertEquals(componentName.flattenToString(), captor.value[0].key)

        verify(mockCallbackOther).onServicesUpdated(capture(captor))
        assertEquals(1, captor.value.size)
        assertEquals(componentName.flattenToString(), captor.value[0].key)
    }

    @Test
    fun testChangeUser() {
        controller.changeUser(UserHandle.of(otherUser))
        executor.runAllReady()
        assertEquals(otherUser, controller.currentUserId)

        val inOrder = inOrder(mockSL)
        inOrder.verify(mockSL).setListening(false)
        inOrder.verify(mockSL).addCallback(any()) // We add a callback because we replaced the SL
        inOrder.verify(mockSL).setListening(true)
        inOrder.verify(mockSL).reload()
    }

    @Test
    fun testChangeUserSendsCorrectServiceUpdate() {
        val serviceInfo = ServiceInfo(componentName)

        val list = listOf(serviceInfo)
        controller.addCallback(mockCallback)

        @Suppress("unchecked_cast")
        val captor: ArgumentCaptor<List<ControlsServiceInfo>> =
            ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<ControlsServiceInfo>>
        executor.runAllReady()
        reset(mockCallback)

        serviceListingCallbackCaptor.value.onServicesReloaded(list)

        executor.runAllReady()
        verify(mockCallback).onServicesUpdated(capture(captor))
        assertEquals(1, captor.value.size)

        reset(mockCallback)
        reset(mockSL)

        val updatedList = listOf(serviceInfo)
        serviceListingCallbackCaptor.value.onServicesReloaded(updatedList)
        controller.changeUser(UserHandle.of(otherUser))
        executor.runAllReady()
        assertEquals(otherUser, controller.currentUserId)

        // this event should was triggered just before the user change, and should
        // be ignored
        verify(mockCallback, never()).onServicesUpdated(any())

        serviceListingCallbackCaptor.value.onServicesReloaded(emptyList<ServiceInfo>())
        executor.runAllReady()

        verify(mockCallback).onServicesUpdated(capture(captor))
        assertEquals(0, captor.value.size)
    }

    @Test
    fun test_nullPanelActivity() {
        val list = listOf(ServiceInfo(componentName))
        serviceListingCallbackCaptor.value.onServicesReloaded(list)

        executor.runAllReady()

        assertNull(controller.getCurrentServices()[0].panelActivity)
    }

    @Test
    fun testNoActivity_nullPanel() {
        val serviceInfo = ServiceInfo(componentName, activityName)

        val list = listOf(serviceInfo)
        serviceListingCallbackCaptor.value.onServicesReloaded(list)

        executor.runAllReady()

        assertNull(controller.getCurrentServices()[0].panelActivity)
    }

    @Test
    fun testActivityWithoutPermission_nullPanel() {
        val serviceInfo = ServiceInfo(componentName, activityName)

        setUpQueryResult(listOf(ActivityInfo(activityName)))

        val list = listOf(serviceInfo)
        serviceListingCallbackCaptor.value.onServicesReloaded(list)

        executor.runAllReady()

        assertNull(controller.getCurrentServices()[0].panelActivity)
    }

    @Test
    fun testActivityPermissionNotExported_nullPanel() {
        val serviceInfo = ServiceInfo(componentName, activityName)

        setUpQueryResult(
            listOf(ActivityInfo(activityName, permission = Manifest.permission.BIND_CONTROLS))
        )

        val list = listOf(serviceInfo)
        serviceListingCallbackCaptor.value.onServicesReloaded(list)

        executor.runAllReady()

        assertNull(controller.getCurrentServices()[0].panelActivity)
    }

    @Test
    fun testActivityDisabled_nullPanel() {
        val serviceInfo = ServiceInfo(componentName, activityName)

        setUpQueryResult(
            listOf(
                ActivityInfo(
                    activityName,
                    exported = true,
                    permission = Manifest.permission.BIND_CONTROLS
                )
            )
        )

        val list = listOf(serviceInfo)
        serviceListingCallbackCaptor.value.onServicesReloaded(list)

        executor.runAllReady()

        assertNull(controller.getCurrentServices()[0].panelActivity)
    }

    @Test
    fun testActivityEnabled_correctPanel() {
        val serviceInfo = ServiceInfo(componentName, activityName)

        `when`(packageManager.getComponentEnabledSetting(eq(activityName)))
            .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_ENABLED)

        setUpQueryResult(
            listOf(
                ActivityInfo(
                    activityName,
                    exported = true,
                    permission = Manifest.permission.BIND_CONTROLS
                )
            )
        )

        val list = listOf(serviceInfo)
        serviceListingCallbackCaptor.value.onServicesReloaded(list)

        executor.runAllReady()

        assertEquals(activityName, controller.getCurrentServices()[0].panelActivity)
    }

    @Test
    fun testActivityDefaultEnabled_correctPanel() {
        val serviceInfo = ServiceInfo(componentName, activityName)

        `when`(packageManager.getComponentEnabledSetting(eq(activityName)))
            .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)

        setUpQueryResult(
            listOf(
                ActivityInfo(
                    activityName,
                    enabled = true,
                    exported = true,
                    permission = Manifest.permission.BIND_CONTROLS
                )
            )
        )

        val list = listOf(serviceInfo)
        serviceListingCallbackCaptor.value.onServicesReloaded(list)

        executor.runAllReady()

        assertEquals(activityName, controller.getCurrentServices()[0].panelActivity)
    }

    @Test
    fun testActivityDefaultDisabled_nullPanel() {
        val serviceInfo = ServiceInfo(componentName, activityName)

        `when`(packageManager.getComponentEnabledSetting(eq(activityName)))
            .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)

        setUpQueryResult(
            listOf(
                ActivityInfo(
                    activityName,
                    enabled = false,
                    exported = true,
                    permission = Manifest.permission.BIND_CONTROLS
                )
            )
        )

        val list = listOf(serviceInfo)
        serviceListingCallbackCaptor.value.onServicesReloaded(list)

        executor.runAllReady()

        assertNull(controller.getCurrentServices()[0].panelActivity)
    }

    @Test
    fun testActivityDifferentPackage_nullPanel() {
        val serviceInfo = ServiceInfo(componentName, ComponentName("other_package", "cls"))

        `when`(packageManager.getComponentEnabledSetting(eq(activityName)))
            .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)

        setUpQueryResult(
            listOf(
                ActivityInfo(
                    activityName,
                    enabled = true,
                    exported = true,
                    permission = Manifest.permission.BIND_CONTROLS
                )
            )
        )

        val list = listOf(serviceInfo)
        serviceListingCallbackCaptor.value.onServicesReloaded(list)

        executor.runAllReady()

        assertNull(controller.getCurrentServices()[0].panelActivity)
    }

    @Test
    fun testPackageNotPreferred_correctPanel() {
        mContext.orCreateTestableResources.addOverride(
            R.array.config_controlsPreferredPackages,
            arrayOf<String>()
        )

        val serviceInfo = ServiceInfo(componentName, activityName)

        `when`(packageManager.getComponentEnabledSetting(eq(activityName)))
            .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_ENABLED)

        setUpQueryResult(
            listOf(
                ActivityInfo(
                    activityName,
                    exported = true,
                    permission = Manifest.permission.BIND_CONTROLS
                )
            )
        )

        val list = listOf(serviceInfo)
        serviceListingCallbackCaptor.value.onServicesReloaded(list)

        executor.runAllReady()

        assertEquals(activityName, controller.getCurrentServices()[0].panelActivity)
    }

    @Test
    fun testListingsNotModifiedByCallback() {
        // This test checks that if the list passed to the callback is modified, it has no effect
        // in the resulting services
        val list = mutableListOf<ServiceInfo>()
        serviceListingCallbackCaptor.value.onServicesReloaded(list)

        list.add(ServiceInfo(ComponentName("a", "b")))
        executor.runAllReady()

        assertTrue(controller.getCurrentServices().isEmpty())
    }

    @Test
    fun testForceReloadQueriesPackageManager() {
        val user = 10
        `when`(userTracker.userHandle).thenReturn(UserHandle.of(user))

        controller.forceReload()
        verify(packageManager)
            .queryIntentServicesAsUser(
                argThat(IntentMatcherAction(ControlsProviderService.SERVICE_CONTROLS)),
                argThat(
                    FlagsMatcher(
                        PackageManager.GET_META_DATA.toLong() or
                            PackageManager.GET_SERVICES.toLong() or
                            PackageManager.MATCH_DIRECT_BOOT_AWARE.toLong() or
                            PackageManager.MATCH_DIRECT_BOOT_UNAWARE.toLong()
                    )
                ),
                eq(UserHandle.of(user))
            )
    }

    @Test
    fun testForceReloadUpdatesList() {
        val resolveInfo = ResolveInfo()
        resolveInfo.serviceInfo = ServiceInfo(componentName)

        `when`(
                packageManager.queryIntentServicesAsUser(
                    argThat(IntentMatcherAction(ControlsProviderService.SERVICE_CONTROLS)),
                    argThat(
                        FlagsMatcher(
                            PackageManager.GET_META_DATA.toLong() or
                                PackageManager.GET_SERVICES.toLong() or
                                PackageManager.MATCH_DIRECT_BOOT_AWARE.toLong() or
                                PackageManager.MATCH_DIRECT_BOOT_UNAWARE.toLong()
                        )
                    ),
                    any<UserHandle>()
                )
            )
            .thenReturn(listOf(resolveInfo))

        controller.forceReload()

        val services = controller.getCurrentServices()
        assertThat(services.size).isEqualTo(1)
        assertThat(services[0].serviceInfo.componentName).isEqualTo(componentName)
    }

    @Test
    fun testForceReloadCallsListeners() {
        controller.addCallback(mockCallback)
        executor.runAllReady()

        @Suppress("unchecked_cast")
        val captor: ArgumentCaptor<List<ControlsServiceInfo>> =
            ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<ControlsServiceInfo>>

        val resolveInfo = ResolveInfo()
        resolveInfo.serviceInfo = ServiceInfo(componentName)

        `when`(
                packageManager.queryIntentServicesAsUser(
                    any(),
                    any<PackageManager.ResolveInfoFlags>(),
                    any<UserHandle>()
                )
            )
            .thenReturn(listOf(resolveInfo))

        reset(mockCallback)
        controller.forceReload()

        verify(mockCallback).onServicesUpdated(capture(captor))

        val services = captor.value

        assertThat(services.size).isEqualTo(1)
        assertThat(services[0].serviceInfo.componentName).isEqualTo(componentName)
    }

    @Test
    fun testNoPanelIfMultiWindowNotSupported() {
        `when`(activityTaskManagerProxy.supportsMultiWindow(any())).thenReturn(false)

        val serviceInfo = ServiceInfo(componentName, activityName)

        `when`(packageManager.getComponentEnabledSetting(eq(activityName)))
            .thenReturn(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)

        setUpQueryResult(
            listOf(
                ActivityInfo(
                    activityName,
                    enabled = true,
                    exported = true,
                    permission = Manifest.permission.BIND_CONTROLS
                )
            )
        )

        val list = listOf(serviceInfo)
        serviceListingCallbackCaptor.value.onServicesReloaded(list)

        executor.runAllReady()

        assertNull(controller.getCurrentServices()[0].panelActivity)
    }

    @Test
    fun dumpAndAddRemoveCallback_willNotThrowConcurrentModificationException() {
        val repeat = 100
        controller.addCallback(mockCallback) // 1 extra callback increases the duration of iteration

        // the goal of these two barriers is to make the modify and iterate run concurrently
        val startSignal = CountDownLatch(2)
        val doneSignal = CountDownLatch(2)
        val modifyRunnable = Runnable {
            for (i in 1..repeat) {
                controller.addCallback(mockCallbackOther)
                executor.runAllReady()
                controller.removeCallback(mockCallbackOther)
                executor.runAllReady()
            }
        }
        val printWriter = mock<PrintWriter>()
        val arr = arrayOf<String>()
        val iterateRunnable = Runnable {
            for (i in 1..repeat) {
                controller.dump(printWriter, arr)
            }
        }

        val workerThread = Thread(Worker(startSignal, doneSignal, modifyRunnable))
        workerThread.start()
        val workerThreadOther = Thread(Worker(startSignal, doneSignal, iterateRunnable))
        workerThreadOther.start()
        doneSignal.await()
        workerThread.interrupt()
        workerThreadOther.interrupt()
    }

    class Worker : Runnable {
        private val startSignal: CountDownLatch
        private val doneSignal: CountDownLatch
        private val runnable: Runnable

        constructor(start: CountDownLatch, done: CountDownLatch, run: Runnable) {
            startSignal = start
            doneSignal = done
            runnable = run
        }

        override fun run() {
            try {
                startSignal.countDown()
                startSignal.await()
                runnable.run()
                doneSignal.countDown()
            } catch (ex: InterruptedException) {
                return
            }
        }
    }

    private fun ServiceInfo(
        componentName: ComponentName,
        panelActivityComponentName: ComponentName? = null
    ): ServiceInfo {
        return ServiceInfo().apply {
            packageName = componentName.packageName
            name = componentName.className
            panelActivityComponentName?.let {
                metaData =
                    Bundle().apply {
                        putString(
                            ControlsProviderService.META_DATA_PANEL_ACTIVITY,
                            it.flattenToShortString()
                        )
                    }
            }
        }
    }

    private fun ActivityInfo(
        componentName: ComponentName,
        exported: Boolean = false,
        enabled: Boolean = true,
        permission: String? = null
    ): ActivityInfo {
        return ActivityInfo().apply {
            packageName = componentName.packageName
            name = componentName.className
            this.permission = permission
            this.exported = exported
            this.enabled = enabled
        }
    }

    private fun setUpQueryResult(infos: List<ActivityInfo>) {
        `when`(
                packageManager.queryIntentActivitiesAsUser(
                    argThat(IntentMatcherComponent(activityName)),
                    argThat(FlagsMatcher(FLAGS)),
                    eq(UserHandle.of(user))
                )
            )
            .thenReturn(infos.map { ResolveInfo().apply { activityInfo = it } })
    }

    private class IntentMatcherComponent(private val componentName: ComponentName) :
        ArgumentMatcher<Intent> {
        override fun matches(argument: Intent?): Boolean {
            return argument?.component == componentName
        }
    }

    private class IntentMatcherAction(private val action: String) : ArgumentMatcher<Intent> {
        override fun matches(argument: Intent?): Boolean {
            return argument?.action == action
        }
    }

    private class FlagsMatcher(private val flags: Long) :
        ArgumentMatcher<PackageManager.ResolveInfoFlags> {
        override fun matches(argument: PackageManager.ResolveInfoFlags?): Boolean {
            return flags == argument?.value
        }
    }
}
