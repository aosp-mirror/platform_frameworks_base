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
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.drawable.Drawable
import android.os.UserHandle
import android.service.controls.ControlsProviderService
import android.service.controls.flags.Flags.FLAG_HOME_PANEL_DREAM
import android.testing.TestableLooper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.controls.ControlsMetricsLogger
import com.android.systemui.controls.ControlsServiceInfo
import com.android.systemui.controls.CustomIconCache
import com.android.systemui.controls.controller.ControlsController
import com.android.systemui.controls.controller.StructureInfo
import com.android.systemui.controls.management.ControlsListingController
import com.android.systemui.controls.management.ControlsProviderSelectorActivity
import com.android.systemui.controls.panels.AuthorizedPanelsRepository
import com.android.systemui.controls.panels.SelectedComponentRepository
import com.android.systemui.controls.panels.selectedComponentRepository
import com.android.systemui.controls.settings.FakeControlsSettingsRepository
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.res.R
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.phone.SystemUIDialog
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.testKosmos
import com.android.systemui.util.FakeSystemUIDialogController
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.capture
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.android.wm.shell.taskview.TaskView
import com.android.wm.shell.taskview.TaskViewFactory
import com.google.common.truth.Truth.assertThat
import java.util.Optional
import java.util.function.Consumer
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.isNull
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
@TestableLooper.RunWithLooper
class ControlsUiControllerImplTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    @Mock lateinit var controlsController: ControlsController
    @Mock lateinit var controlsListingController: ControlsListingController
    @Mock lateinit var controlActionCoordinator: ControlActionCoordinator
    @Mock lateinit var activityStarter: ActivityStarter
    @Mock lateinit var iconCache: CustomIconCache
    @Mock lateinit var controlsMetricsLogger: ControlsMetricsLogger
    @Mock lateinit var keyguardStateController: KeyguardStateController
    @Mock lateinit var userTracker: UserTracker
    @Mock lateinit var taskViewFactory: TaskViewFactory
    @Mock lateinit var dumpManager: DumpManager
    @Mock lateinit var authorizedPanelsRepository: AuthorizedPanelsRepository
    @Mock lateinit var featureFlags: FeatureFlags
    @Mock lateinit var packageManager: PackageManager
    @Mock lateinit var systemUIDialogFactory: SystemUIDialog.Factory

    private val preferredPanelRepository = kosmos.selectedComponentRepository
    private lateinit var fakeDialogController: FakeSystemUIDialogController
    private val uiExecutor = FakeExecutor(FakeSystemClock())
    private val bgExecutor = FakeExecutor(FakeSystemClock())

    private lateinit var controlsSettingsRepository: FakeControlsSettingsRepository
    private lateinit var parent: FrameLayout
    private lateinit var underTest: ControlsUiControllerImpl

    private var isKeyguardDismissed: Boolean = true
    private var isRemoveAppDialogCreated: Boolean = false

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)

        fakeDialogController = FakeSystemUIDialogController(mContext)
        whenever(systemUIDialogFactory.create(any(Context::class.java)))
            .thenReturn(fakeDialogController.dialog)
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
                { controlsController },
                context,
                packageManager,
                uiExecutor,
                bgExecutor,
                { controlsListingController },
                controlActionCoordinator,
                activityStarter,
                iconCache,
                controlsMetricsLogger,
                keyguardStateController,
                userTracker,
                Optional.of(taskViewFactory),
                controlsSettingsRepository,
                authorizedPanelsRepository,
                preferredPanelRepository,
                featureFlags,
                ControlsDialogsFactory(systemUIDialogFactory),
                dumpManager,
            )
        `when`(userTracker.userId).thenReturn(0)
        `when`(userTracker.userHandle).thenReturn(UserHandle.of(0))
        doAnswer {
                if (isKeyguardDismissed) {
                    it.getArgument<ActivityStarter.OnDismissAction>(0).onDismiss()
                } else {
                    it.getArgument<Runnable?>(1)?.run()
                }
            }
            .whenever(activityStarter)
            .dismissKeyguardThenExecute(any(), isNull(), any())
    }

    @Test
    fun testGetPreferredPanel() {
        val panel = SelectedItem.PanelItem("App name", ComponentName("pkg", "cls"))

        preferredPanelRepository.setSelectedComponent(
            SelectedComponentRepository.SelectedComponent(
                name = panel.appName.toString(),
                componentName = panel.componentName,
                isPanel = true,
            )
        )

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
        val packageName = "pkg"
        `when`(authorizedPanelsRepository.getAuthorizedPanels()).thenReturn(setOf(packageName))
        val panel = SelectedItem.PanelItem("App name", ComponentName(packageName, "cls"))
        val serviceInfo = setUpPanel(panel)

        underTest.show(parent, {}, context)

        val captor = argumentCaptor<ControlsListingController.ControlsListingCallback>()

        verify(controlsListingController).addCallback(capture(captor))

        captor.value.onServicesUpdated(listOf(serviceInfo))
        FakeExecutor.exhaustExecutors(uiExecutor, bgExecutor)

        verify(taskViewFactory).create(eq(context), eq(uiExecutor), any())
    }

    @Test
    fun testSingleAppHeaderIsNotClickable() {
        mockLayoutInflater()
        val packageName = "pkg"
        `when`(authorizedPanelsRepository.getAuthorizedPanels()).thenReturn(setOf(packageName))
        val panel = SelectedItem.PanelItem("App name", ComponentName(packageName, "cls"))
        val serviceInfo = setUpPanel(panel)

        underTest.show(parent, {}, context)

        val captor = argumentCaptor<ControlsListingController.ControlsListingCallback>()

        verify(controlsListingController).addCallback(capture(captor))

        captor.value.onServicesUpdated(listOf(serviceInfo))
        FakeExecutor.exhaustExecutors(uiExecutor, bgExecutor)

        val header: View = parent.requireViewById(R.id.controls_header)
        assertThat(header.isClickable).isFalse()
        assertThat(header.hasOnClickListeners()).isFalse()
    }

    @Test
    fun testMultipleAppHeaderIsClickable() {
        mockLayoutInflater()
        val packageName1 = "pkg"
        val panel1 = SelectedItem.PanelItem("App name 1", ComponentName(packageName1, "cls"))
        val serviceInfo1 = setUpPanel(panel1)

        val packageName2 = "pkg"
        val panel2 = SelectedItem.PanelItem("App name 2", ComponentName(packageName2, "cls"))
        val serviceInfo2 = setUpPanel(panel2)

        `when`(authorizedPanelsRepository.getAuthorizedPanels())
            .thenReturn(setOf(packageName1, packageName2))

        underTest.show(parent, {}, context)

        val captor = argumentCaptor<ControlsListingController.ControlsListingCallback>()

        verify(controlsListingController).addCallback(capture(captor))

        captor.value.onServicesUpdated(listOf(serviceInfo1, serviceInfo2))
        FakeExecutor.exhaustExecutors(uiExecutor, bgExecutor)

        val header: View = parent.requireViewById(R.id.app_or_structure_spinner)
        assertThat(header.isClickable).isTrue()
        assertThat(header.hasOnClickListeners()).isTrue()
    }

    @Test
    fun testPanelControllerStartActivityWithCorrectArguments() {
        mSetFlagsRule.disableFlags(FLAG_HOME_PANEL_DREAM)
        mockLayoutInflater()
        val packageName = "pkg"
        `when`(authorizedPanelsRepository.getAuthorizedPanels()).thenReturn(setOf(packageName))
        controlsSettingsRepository.setAllowActionOnTrivialControlsInLockscreen(true)

        val panel = SelectedItem.PanelItem("App name", ComponentName(packageName, "cls"))
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
            // We should not include controls surface extra if the home panel dream flag is off.
            assertThat(intent.getIntExtra(ControlsProviderService.EXTRA_CONTROLS_SURFACE, -10))
                .isEqualTo(-10)
        }
    }

    @Test
    fun testPanelControllerStartActivityWithHomePanelDreamEnabled() {
        mSetFlagsRule.enableFlags(FLAG_HOME_PANEL_DREAM)
        mockLayoutInflater()
        val packageName = "pkg"
        `when`(authorizedPanelsRepository.getAuthorizedPanels()).thenReturn(setOf(packageName))
        controlsSettingsRepository.setAllowActionOnTrivialControlsInLockscreen(true)

        val panel = SelectedItem.PanelItem("App name", ComponentName(packageName, "cls"))
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
            // We should not include controls surface extra if the home panel dream flag is off.
            assertThat(intent.getIntExtra(ControlsProviderService.EXTRA_CONTROLS_SURFACE, -10))
                .isEqualTo(ControlsProviderService.CONTROLS_SURFACE_ACTIVITY_PANEL)
        }
    }

    @Test
    fun testPendingIntentExtrasAreModified() {
        mockLayoutInflater()
        val packageName = "pkg"
        `when`(authorizedPanelsRepository.getAuthorizedPanels()).thenReturn(setOf(packageName))
        controlsSettingsRepository.setAllowActionOnTrivialControlsInLockscreen(true)

        val panel = SelectedItem.PanelItem("App name", ComponentName(packageName, "cls"))
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

        underTest.hide(parent)

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
                    StructureInfo(
                        checkNotNull(ComponentName.unflattenFromString("pkg/.cls1")),
                        "a",
                        ArrayList()
                    )
                ),
            )
        preferredPanelRepository.setSelectedComponent(
            SelectedComponentRepository.SelectedComponent(selectedItems[0])
        )

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

    @Test
    fun testRemoveViewsOnlyForParentPassedInHide() {
        underTest.show(parent, {}, context)
        parent.addView(View(context))

        val mockParent: ViewGroup = mock()

        underTest.hide(mockParent)

        verify(mockParent).removeAllViews()
        assertThat(parent.childCount).isGreaterThan(0)
    }

    @Test
    fun testHideDifferentParentDoesntCancelListeners() {
        underTest.show(parent, {}, context)
        underTest.hide(mock())

        verify(controlsController, never()).unsubscribe()
        verify(controlsListingController, never()).removeCallback(any())
    }

    @Test
    fun testRemovingAppsRemovesFavorite() {
        val componentName = ComponentName(context, "cls")
        whenever(controlsController.removeFavorites(eq(componentName))).thenReturn(true)
        val panel = SelectedItem.PanelItem("App name", componentName)
        preferredPanelRepository.setSelectedComponent(
            SelectedComponentRepository.SelectedComponent(panel)
        )
        underTest.show(parent, {}, context)
        underTest.startRemovingApp(componentName, "Test App")

        fakeDialogController.clickPositive()

        verify(controlsController).removeFavorites(eq(componentName))
        assertThat(underTest.getPreferredSelectedItem(emptyList()))
            .isEqualTo(SelectedItem.EMPTY_SELECTION)
        assertThat(preferredPanelRepository.shouldAddDefaultComponent()).isFalse()
        assertThat(preferredPanelRepository.getSelectedComponent()).isNull()
    }

    @Test
    fun testKeyguardRemovingAppsNotShowingDialog() {
        isKeyguardDismissed = false
        val componentName = ComponentName(context, "cls")
        whenever(controlsController.removeFavorites(eq(componentName))).thenReturn(true)
        val panel = SelectedItem.PanelItem("App name", componentName)
        preferredPanelRepository.setSelectedComponent(
            SelectedComponentRepository.SelectedComponent(panel)
        )
        underTest.show(parent, {}, context)
        underTest.startRemovingApp(componentName, "Test App")

        assertThat(isRemoveAppDialogCreated).isFalse()
        verify(controlsController, never()).removeFavorites(eq(componentName))
        assertThat(underTest.getPreferredSelectedItem(emptyList())).isEqualTo(panel)
        assertThat(preferredPanelRepository.shouldAddDefaultComponent()).isTrue()
        assertThat(preferredPanelRepository.getSelectedComponent())
            .isEqualTo(SelectedComponentRepository.SelectedComponent(panel))
    }

    @Test
    fun testCancelRemovingAppsDoesntRemoveFavorite() {
        val componentName = ComponentName(context, "cls")
        whenever(controlsController.removeFavorites(eq(componentName))).thenReturn(true)
        val panel = SelectedItem.PanelItem("App name", componentName)
        preferredPanelRepository.setSelectedComponent(
            SelectedComponentRepository.SelectedComponent(panel)
        )
        underTest.show(parent, {}, context)
        underTest.startRemovingApp(componentName, "Test App")

        fakeDialogController.clickNeutral()

        verify(controlsController, never()).removeFavorites(eq(componentName))
        assertThat(underTest.getPreferredSelectedItem(emptyList())).isEqualTo(panel)
        assertThat(preferredPanelRepository.shouldAddDefaultComponent()).isTrue()
        assertThat(preferredPanelRepository.getSelectedComponent())
            .isEqualTo(SelectedComponentRepository.SelectedComponent(panel))
    }

    @Test
    fun testHideCancelsTheRemoveAppDialog() {
        val componentName = ComponentName(context, "cls")
        underTest.show(parent, {}, context)
        underTest.startRemovingApp(componentName, "Test App")

        underTest.hide(parent)

        verify(fakeDialogController.dialog).cancel()
    }

    @Test
    fun testOnRotationWithPanelUpdateBoundsCalled() {
        mockLayoutInflater()
        val packageName = "pkg"
        `when`(authorizedPanelsRepository.getAuthorizedPanels()).thenReturn(setOf(packageName))
        val panel = SelectedItem.PanelItem("App name", ComponentName(packageName, "cls"))
        val serviceInfo = setUpPanel(panel)

        underTest.show(parent, {}, context)

        val captor = argumentCaptor<ControlsListingController.ControlsListingCallback>()

        verify(controlsListingController).addCallback(capture(captor))
        captor.value.onServicesUpdated(listOf(serviceInfo))
        FakeExecutor.exhaustExecutors(uiExecutor, bgExecutor)

        val taskViewConsumerCaptor = argumentCaptor<Consumer<TaskView>>()
        verify(taskViewFactory).create(eq(context), eq(uiExecutor), capture(taskViewConsumerCaptor))

        val taskView: TaskView = mock {
            `when`(this.post(any())).thenAnswer {
                uiExecutor.execute(it.arguments[0] as Runnable)
                true
            }
        }

        taskViewConsumerCaptor.value.accept(taskView)

        underTest.onSizeChange()
        verify(taskView).onLocationChanged()
    }

    private fun setUpPanel(panel: SelectedItem.PanelItem): ControlsServiceInfo {
        val activity = ComponentName(context, "activity")
        preferredPanelRepository.setSelectedComponent(
            SelectedComponentRepository.SelectedComponent(panel)
        )
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
            doReturn(label).whenever(this).loadLabel()
            doReturn(mock<Drawable>()).whenever(this).loadIcon()
            doReturn(panelComponentName).whenever(this).panelActivity
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
