/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.controls.ui

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.UserHandle
import android.service.controls.ControlsProviderService
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.ControlsMetricsLogger
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.CustomIconCache
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.controller.StructureInfo
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.management.ControlsProviderSelectorActivity
import com.android.systemui.controls.settings.FakeControlsSettingsRepository
import com.android.systemui.dump.DumpManager
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.shade.ShadeController
import com.android.systemui.statusbar.policy.DeviceControlsControllerImpl
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.FakeSharedPreferences
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.android.wm.shell.TaskView
import com.android.wm.shell.TaskViewFactory
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import java.util.Optional
import java.util.function.Consumer
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class ControlsUiControllerImplTest : SysuiTestCase() {
    @Mock lateinit var controlsController: ControlsController
    @Mock lateinit var controlsListingController: ControlsListingController
    @Mock lateinit var controlActionCoordinator: ControlActionCoordinator
    @Mock lateinit var activityStarter: ActivityStarter
    @Mock lateinit var shadeController: ShadeController
    @Mock lateinit var iconCache: CustomIconCache
    @Mock lateinit var controlsMetricsLogger: ControlsMetricsLogger
    @Mock lateinit var keyguardStateController: KeyguardStateController
    @Mock lateinit var userFileManager: UserFileManager
    @Mock lateinit var userTracker: UserTracker
    @Mock lateinit var taskViewFactory: TaskViewFactory
    @Mock lateinit var dumpManager: DumpManager
    val sharedPreferences = FakeSharedPreferences()
    lateinit var controlsSettingsRepository: FakeControlsSettingsRepository

    var uiExecutor = FakeExecutor(FakeSystemClock())
    var bgExecutor = FakeExecutor(FakeSystemClock())
    lateinit var underTest: ControlsUiControllerImpl
    lateinit var parent: FrameLayout

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        controlsSettingsRepository = FakeControlsSettingsRepository()

        // This way, it won't be cloned every time `LayoutInflater.fromContext` is called, but we
        // need to clone it once so we don't modify the original one.
        mContext.addMockSystemService(
            Context.LAYOUT_INFLATER_SERVICE,
            mContext.baseContext
                .getSystemService(LayoutInflater::class.java)!!
                .cloneInContext(mContext)
        )

        parent = FrameLayout(mContext)

        underTest =
            ControlsUiControllerImpl(
                Lazy { controlsController },
                context,
                uiExecutor,
                bgExecutor,
                Lazy { controlsListingController },
                controlActionCoordinator,
                activityStarter,
                iconCache,
                controlsMetricsLogger,
                keyguardStateController,
                userFileManager,
                userTracker,
                Optional.of(taskViewFactory),
                controlsSettingsRepository,
                dumpManager
            )
        `when`(
                userFileManager.getSharedPreferences(
                    DeviceControlsControllerImpl.PREFS_CONTROLS_FILE,
                    0,
                    0
                )
            )
            .thenReturn(sharedPreferences)
        `when`(userFileManager.getSharedPreferences(anyString(), anyInt(), anyInt()))
            .thenReturn(sharedPreferences)
        `when`(userTracker.userId).thenReturn(0)
        `when`(userTracker.userHandle).thenReturn(UserHandle.of(0))
    }

    @Test
    fun testGetPreferredStructure() {
        val structureInfo = mock<StructureInfo>()
        underTest.getPreferredSelectedItem(listOf(structureInfo))
        verify(userFileManager)
            .getSharedPreferences(
                fileName = DeviceControlsControllerImpl.PREFS_CONTROLS_FILE,
                mode = 0,
                userId = 0
            )
    }

    @Test
    fun testGetPreferredStructure_differentUserId() {
        val selectedItems =
            listOf(
                SelectedItem.StructureItem(
                    StructureInfo(ComponentName.unflattenFromString("pkg/.cls1"), "a", ArrayList())
                ),
                SelectedItem.StructureItem(
                    StructureInfo(ComponentName.unflattenFromString("pkg/.cls2"), "b", ArrayList())
                ),
            )
        val structures = selectedItems.map { it.structure }
        sharedPreferences
            .edit()
            .putString("controls_component", selectedItems[0].componentName.flattenToString())
            .putString("controls_structure", selectedItems[0].name.toString())
            .commit()

        val differentSharedPreferences = FakeSharedPreferences()
        differentSharedPreferences
            .edit()
            .putString("controls_component", selectedItems[1].componentName.flattenToString())
            .putString("controls_structure", selectedItems[1].name.toString())
            .commit()

        val previousPreferredStructure = underTest.getPreferredSelectedItem(structures)

        `when`(
                userFileManager.getSharedPreferences(
                    DeviceControlsControllerImpl.PREFS_CONTROLS_FILE,
                    0,
                    1
                )
            )
            .thenReturn(differentSharedPreferences)
        `when`(userTracker.userId).thenReturn(1)

        val currentPreferredStructure = underTest.getPreferredSelectedItem(structures)

        assertThat(previousPreferredStructure).isEqualTo(selectedItems[0])
        assertThat(currentPreferredStructure).isEqualTo(selectedItems[1])
        assertThat(currentPreferredStructure).isNotEqualTo(previousPreferredStructure)
    }

    @Test
    fun testGetPreferredPanel() {
        val panel = SelectedItem.PanelItem("App name", ComponentName("pkg", "cls"))
        sharedPreferences
            .edit()
            .putString("controls_component", panel.componentName.flattenToString())
            .putString("controls_structure", panel.appName.toString())
            .putBoolean("controls_is_panel", true)
            .commit()

        val selected = underTest.getPreferredSelectedItem(emptyList())

        assertThat(selected).isEqualTo(panel)
    }

    @Test
    fun testPanelDoesNotRefreshControls() {
        val panel = SelectedItem.PanelItem("App name", ComponentName("pkg", "cls"))
        setUpPanel(panel)

        underTest.show(parent, {}, context)
        verify(controlsController, never()).refreshStatus(any(), any())
    }

    @Test
    fun testPanelBindsForPanel() {
        val panel = SelectedItem.PanelItem("App name", ComponentName("pkg", "cls"))
        setUpPanel(panel)

        underTest.show(parent, {}, context)
        verify(controlsController).bindComponentForPanel(panel.componentName)
    }

    @Test
    fun testPanelCallsTaskViewFactoryCreate() {
        mockLayoutInflater()
        val panel = SelectedItem.PanelItem("App name", ComponentName("pkg", "cls"))
        val serviceInfo = setUpPanel(panel)

        underTest.show(parent, {}, context)

        val captor = argumentCaptor<ControlsListingController.ControlsListingCallback>()

        verify(controlsListingController).addCallback(capture(captor))

        captor.value.onServicesUpdated(listOf(serviceInfo))
        FakeExecutor.exhaustExecutors(uiExecutor, bgExecutor)

        verify(taskViewFactory).create(eq(context), eq(uiExecutor), any())
    }

    @Test
    fun testPanelControllerStartActivityWithCorrectArguments() {
        mockLayoutInflater()
        controlsSettingsRepository.setAllowActionOnTrivialControlsInLockscreen(true)

        val panel = SelectedItem.PanelItem("App name", ComponentName("pkg", "cls"))
        val serviceInfo = setUpPanel(panel)

        underTest.show(parent, {}, context)

        val captor = argumentCaptor<ControlsListingController.ControlsListingCallback>()

        verify(controlsListingController).addCallback(capture(captor))

        captor.value.onServicesUpdated(listOf(serviceInfo))
        FakeExecutor.exhaustExecutors(uiExecutor, bgExecutor)

        val pendingIntent = verifyPanelCreatedAndStartTaskView()

        with(pendingIntent) {
            assertThat(isActivity).isTrue()
            assertThat(intent.component).isEqualTo(serviceInfo.panelActivity)
            assertThat(
                    intent.getBooleanExtra(
                        ControlsProviderService.EXTRA_LOCKSCREEN_ALLOW_TRIVIAL_CONTROLS,
                        false
                    )
                )
                .isTrue()
        }
    }

    @Test
    fun testPendingIntentExtrasAreModified() {
        mockLayoutInflater()
        controlsSettingsRepository.setAllowActionOnTrivialControlsInLockscreen(true)

        val panel = SelectedItem.PanelItem("App name", ComponentName("pkg", "cls"))
        val serviceInfo = setUpPanel(panel)

        underTest.show(parent, {}, context)

        val captor = argumentCaptor<ControlsListingController.ControlsListingCallback>()

        verify(controlsListingController).addCallback(capture(captor))

        captor.value.onServicesUpdated(listOf(serviceInfo))
        FakeExecutor.exhaustExecutors(uiExecutor, bgExecutor)

        val pendingIntent = verifyPanelCreatedAndStartTaskView()
        assertThat(
                pendingIntent.intent.getBooleanExtra(
                    ControlsProviderService.EXTRA_LOCKSCREEN_ALLOW_TRIVIAL_CONTROLS,
                    false
                )
            )
            .isTrue()

        underTest.hide()

        clearInvocations(controlsListingController, taskViewFactory)
        controlsSettingsRepository.setAllowActionOnTrivialControlsInLockscreen(false)
        underTest.show(parent, {}, context)

        verify(controlsListingController).addCallback(capture(captor))
        captor.value.onServicesUpdated(listOf(serviceInfo))
        FakeExecutor.exhaustExecutors(uiExecutor, bgExecutor)

        val newPendingIntent = verifyPanelCreatedAndStartTaskView()
        assertThat(
                newPendingIntent.intent.getBooleanExtra(
                    ControlsProviderService.EXTRA_LOCKSCREEN_ALLOW_TRIVIAL_CONTROLS,
                    false
                )
            )
            .isFalse()
    }

    @Test
    fun testResolveActivityWhileSeeding_ControlsActivity() {
        whenever(controlsController.addSeedingFavoritesCallback(any())).thenReturn(true)
        assertThat(underTest.resolveActivity()).isEqualTo(ControlsActivity::class.java)
    }

    @Test
    fun testResolveActivityNotSeedingNoFavoritesNoPanels_ControlsProviderSelectorActivity() {
        whenever(controlsController.addSeedingFavoritesCallback(any())).thenReturn(false)
        whenever(controlsController.getFavorites()).thenReturn(emptyList())

        val selectedItems =
            listOf(
                SelectedItem.StructureItem(
                    StructureInfo(ComponentName.unflattenFromString("pkg/.cls1"), "a", ArrayList())
                ),
            )
        sharedPreferences
            .edit()
            .putString("controls_component", selectedItems[0].componentName.flattenToString())
            .putString("controls_structure", selectedItems[0].name.toString())
            .commit()

        assertThat(underTest.resolveActivity())
            .isEqualTo(ControlsProviderSelectorActivity::class.java)
    }

    @Test
    fun testResolveActivityNotSeedingNoDefaultNoFavoritesPanel_ControlsActivity() {
        val panel = SelectedItem.PanelItem("App name", ComponentName("pkg", "cls"))
        val activity = ComponentName("pkg", "activity")
        val csi = ControlsServiceInfo(panel.componentName, panel.appName, activity)
        whenever(controlsController.addSeedingFavoritesCallback(any())).thenReturn(true)
        whenever(controlsController.getFavorites()).thenReturn(emptyList())
        whenever(controlsListingController.getCurrentServices()).thenReturn(listOf(csi))

        assertThat(underTest.resolveActivity()).isEqualTo(ControlsActivity::class.java)
    }

    private fun setUpPanel(panel: SelectedItem.PanelItem): ControlsServiceInfo {
        val activity = ComponentName("pkg", "activity")
        sharedPreferences
            .edit()
            .putString("controls_component", panel.componentName.flattenToString())
            .putString("controls_structure", panel.appName.toString())
            .putBoolean("controls_is_panel", true)
            .commit()
        return ControlsServiceInfo(panel.componentName, panel.appName, activity)
    }

    private fun verifyPanelCreatedAndStartTaskView(): PendingIntent {
        val taskViewConsumerCaptor = argumentCaptor<Consumer<TaskView>>()
        verify(taskViewFactory).create(eq(context), eq(uiExecutor), capture(taskViewConsumerCaptor))

        val taskView: TaskView = mock {
            `when`(this.post(any())).thenAnswer {
                uiExecutor.execute(it.arguments[0] as Runnable)
                true
            }
        }
        // calls PanelTaskViewController#launchTaskView
        taskViewConsumerCaptor.value.accept(taskView)
        val listenerCaptor = argumentCaptor<TaskView.Listener>()
        verify(taskView).setListener(any(), capture(listenerCaptor))
        listenerCaptor.value.onInitialized()
        FakeExecutor.exhaustExecutors(uiExecutor, bgExecutor)

        val pendingIntentCaptor = argumentCaptor<PendingIntent>()
        verify(taskView).startActivity(capture(pendingIntentCaptor), any(), any(), any())
        return pendingIntentCaptor.value
    }

    private fun ControlsServiceInfo(
        componentName: ComponentName,
        label: CharSequence,
        panelComponentName: ComponentName? = null
    ): ControlsServiceInfo {
        val serviceInfo =
            ServiceInfo().apply {
                applicationInfo = ApplicationInfo()
                packageName = componentName.packageName
                name = componentName.className
            }
        return spy(ControlsServiceInfo(mContext, serviceInfo)).apply {
            `when`(loadLabel()).thenReturn(label)
            `when`(loadIcon()).thenReturn(mock())
            `when`(panelActivity).thenReturn(panelComponentName)
        }
    }

    private fun mockLayoutInflater() {
        LayoutInflater.from(context)
            .setPrivateFactory(
                object : LayoutInflater.Factory2 {
                    override fun onCreateView(
                        view: View?,
                        name: String,
                        context: Context,
                        attrs: AttributeSet
                    ): View? {
                        return onCreateView(name, context, attrs)
                    }

                    override fun onCreateView(
                        name: String,
                        context: Context,
                        attrs: AttributeSet
                    ): View? {
                        if (FrameLayout::class.java.simpleName.equals(name)) {
                            val mock: FrameLayout = mock {
                                `when`(this.context).thenReturn(context)
                                `when`(this.id).thenReturn(R.id.controls_panel)
                                `when`(this.requireViewById<View>(any())).thenCallRealMethod()
                                `when`(this.findViewById<View>(R.id.controls_panel))
                                    .thenReturn(this)
                                `when`(this.post(any())).thenAnswer {
                                    uiExecutor.execute(it.arguments[0] as Runnable)
                                    true
                                }
                            }
                            return mock
                        } else {
                            return null
                        }
                    }
                }
            )
    }
}
