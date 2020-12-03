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

package com.android.systemui.car.navigationbar;

import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
import static android.view.WindowInsetsController.APPEARANCE_OPAQUE_STATUS_BARS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Rect;
import android.os.Handler;
import android.os.RemoteException;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableResources;
import android.util.ArrayMap;
import android.view.Display;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.internal.statusbar.IStatusBarService;
import com.android.internal.statusbar.RegisterStatusBarResult;
import com.android.internal.view.AppearanceRegion;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.CarSystemUiTest;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.LightBarTransitionsController;
import com.android.systemui.statusbar.phone.PhoneStatusBarPolicy;
import com.android.systemui.statusbar.phone.StatusBarIconController;
import com.android.systemui.statusbar.phone.SysuiDarkIconDispatcher;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class CarNavigationBarTest extends SysuiTestCase {

    private CarNavigationBar mCarNavigationBar;
    private TestableResources mTestableResources;
    private Handler mHandler;

    @Mock
    private CarNavigationBarController mCarNavigationBarController;
    @Mock
    private LightBarController mLightBarController;
    @Mock
    private SysuiDarkIconDispatcher mStatusBarIconController;
    @Mock
    private LightBarTransitionsController mLightBarTransitionsController;
    @Mock
    private WindowManager mWindowManager;
    @Mock
    private CarDeviceProvisionedController mDeviceProvisionedController;
    @Mock
    private AutoHideController mAutoHideController;
    @Mock
    private ButtonSelectionStateListener mButtonSelectionStateListener;
    @Mock
    private ButtonRoleHolderController mButtonRoleHolderController;
    @Mock
    private IStatusBarService mBarService;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private ButtonSelectionStateController mButtonSelectionStateController;
    @Mock
    private PhoneStatusBarPolicy mIconPolicy;
    @Mock
    private StatusBarIconController mIconController;

    private RegisterStatusBarResult mBarResult;
    private AppearanceRegion[] mAppearanceRegions;
    private FakeExecutor mUiBgExecutor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mTestableResources = mContext.getOrCreateTestableResources();
        mHandler = Handler.getMain();
        mUiBgExecutor = new FakeExecutor(new FakeSystemClock());
        when(mStatusBarIconController.getTransitionsController()).thenReturn(
                mLightBarTransitionsController);
        mAppearanceRegions = new AppearanceRegion[] {
                new AppearanceRegion(APPEARANCE_LIGHT_STATUS_BARS, new Rect())
        };
        mBarResult = new RegisterStatusBarResult(
                /* icons= */ new ArrayMap<>(),
                /* disabledFlags1= */ 0,
                /* appearance= */ 0,
                mAppearanceRegions,
                /* imeWindowVis= */ 0,
                /* imeBackDisposition= */ 0,
                /* showImeSwitcher= */ false,
                /* disabledFlags2= */ 0,
                /* imeToken= */ null,
                /* navbarColorMangedByIme= */ false,
                /* appFullscreen= */ false,
                /* appImmersive= */ false,
                /* transientBarTypes= */ new int[]{});
        try {
            when(mBarService.registerStatusBar(any())).thenReturn(mBarResult);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        mCarNavigationBar = new CarNavigationBar(mContext, mTestableResources.getResources(),
                mCarNavigationBarController, mLightBarController, mStatusBarIconController,
                mWindowManager, mDeviceProvisionedController, new CommandQueue(mContext),
                mAutoHideController, mButtonSelectionStateListener, mHandler, mUiBgExecutor,
                mBarService, () -> mKeyguardStateController, () -> mIconPolicy,
                () -> mIconController, new SystemBarConfigs(mTestableResources.getResources()));
    }

    @Test
    public void restartNavbars_refreshesTaskChanged() {
        mTestableResources.addOverride(R.bool.config_enableTopNavigationBar, true);
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        ArgumentCaptor<CarDeviceProvisionedController.DeviceProvisionedListener>
                deviceProvisionedCallbackCaptor = ArgumentCaptor.forClass(
                CarDeviceProvisionedController.DeviceProvisionedListener.class);
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(true);
        mCarNavigationBar.start();
        // switching the currentUserSetup value to force restart the navbars.
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(false);
        verify(mDeviceProvisionedController).addCallback(deviceProvisionedCallbackCaptor.capture());

        deviceProvisionedCallbackCaptor.getValue().onUserSwitched();
        waitForIdleSync(mHandler);

        verify(mButtonSelectionStateListener).onTaskStackChanged();
    }

    @Test
    public void restartNavBars_newUserNotSetupWithKeyguardShowing_showsKeyguardButtons() {
        mTestableResources.addOverride(R.bool.config_enableTopNavigationBar, true);
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        ArgumentCaptor<CarDeviceProvisionedController.DeviceProvisionedListener>
                deviceProvisionedCallbackCaptor = ArgumentCaptor.forClass(
                CarDeviceProvisionedController.DeviceProvisionedListener.class);
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(true);
        mCarNavigationBar.start();
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        // switching the currentUserSetup value to force restart the navbars.
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(false);
        verify(mDeviceProvisionedController).addCallback(deviceProvisionedCallbackCaptor.capture());

        deviceProvisionedCallbackCaptor.getValue().onUserSwitched();
        waitForIdleSync(mHandler);

        verify(mCarNavigationBarController).showAllKeyguardButtons(false);
    }

    @Test
    public void restartNavbars_newUserIsSetupWithKeyguardHidden_hidesKeyguardButtons() {
        mTestableResources.addOverride(R.bool.config_enableTopNavigationBar, true);
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        ArgumentCaptor<CarDeviceProvisionedController.DeviceProvisionedListener>
                deviceProvisionedCallbackCaptor = ArgumentCaptor.forClass(
                CarDeviceProvisionedController.DeviceProvisionedListener.class);
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(true);
        mCarNavigationBar.start();
        when(mKeyguardStateController.isShowing()).thenReturn(true);
        // switching the currentUserSetup value to force restart the navbars.
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(false);
        verify(mDeviceProvisionedController).addCallback(deviceProvisionedCallbackCaptor.capture());
        deviceProvisionedCallbackCaptor.getValue().onUserSwitched();
        waitForIdleSync(mHandler);
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(true);
        when(mKeyguardStateController.isShowing()).thenReturn(false);

        deviceProvisionedCallbackCaptor.getValue().onUserSetupChanged();
        waitForIdleSync(mHandler);

        verify(mCarNavigationBarController).hideAllKeyguardButtons(true);
    }

    @Test
    public void restartNavBars_lightAppearance_darkensAllIcons() {
        mAppearanceRegions[0] = new AppearanceRegion(APPEARANCE_LIGHT_STATUS_BARS, new Rect());

        mCarNavigationBar.start();

        verify(mLightBarTransitionsController).setIconsDark(
                /* dark= */ true, /* animate= */ false);
    }

    @Test
    public void restartNavBars_opaqueAppearance_lightensAllIcons() {
        mAppearanceRegions[0] = new AppearanceRegion(APPEARANCE_OPAQUE_STATUS_BARS, new Rect());

        mCarNavigationBar.start();

        verify(mLightBarTransitionsController).setIconsDark(
                /* dark= */ false, /* animate= */ false);
    }

    @Test
    public void showTransient_wrongDisplayId_transientModeNotUpdated() {
        mTestableResources.addOverride(R.bool.config_enableTopNavigationBar, true);
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(true);
        mCarNavigationBar.start();

        int randomDisplay = Display.DEFAULT_DISPLAY + 10;
        int[] insetTypes = new int[]{};
        mCarNavigationBar.showTransient(randomDisplay, insetTypes);

        assertThat(mCarNavigationBar.isStatusBarTransientShown()).isFalse();
    }

    @Test
    public void showTransient_correctDisplayId_noStatusBarInset_transientModeNotUpdated() {
        mTestableResources.addOverride(R.bool.config_enableTopNavigationBar, true);
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(true);
        mCarNavigationBar.start();

        int[] insetTypes = new int[]{};
        mCarNavigationBar.showTransient(Display.DEFAULT_DISPLAY, insetTypes);

        assertThat(mCarNavigationBar.isStatusBarTransientShown()).isFalse();
    }

    @Test
    public void showTransient_correctDisplayId_statusBarInset_transientModeUpdated() {
        mTestableResources.addOverride(R.bool.config_enableTopNavigationBar, true);
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(true);
        mCarNavigationBar.start();

        int[] insetTypes = new int[]{ITYPE_STATUS_BAR};
        mCarNavigationBar.showTransient(Display.DEFAULT_DISPLAY, insetTypes);

        assertThat(mCarNavigationBar.isStatusBarTransientShown()).isTrue();
    }

    @Test
    public void showTransient_correctDisplayId_noNavBarInset_transientModeNotUpdated() {
        mTestableResources.addOverride(R.bool.config_enableTopNavigationBar, true);
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(true);
        mCarNavigationBar.start();

        int[] insetTypes = new int[]{};
        mCarNavigationBar.showTransient(Display.DEFAULT_DISPLAY, insetTypes);

        assertThat(mCarNavigationBar.isNavBarTransientShown()).isFalse();
    }

    @Test
    public void showTransient_correctDisplayId_navBarInset_transientModeUpdated() {
        mTestableResources.addOverride(R.bool.config_enableTopNavigationBar, true);
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(true);
        mCarNavigationBar.start();

        int[] insetTypes = new int[]{ITYPE_NAVIGATION_BAR};
        mCarNavigationBar.showTransient(Display.DEFAULT_DISPLAY, insetTypes);

        assertThat(mCarNavigationBar.isNavBarTransientShown()).isTrue();
    }

    @Test
    public void abortTransient_wrongDisplayId_transientModeNotCleared() {
        mTestableResources.addOverride(R.bool.config_enableTopNavigationBar, true);
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(true);
        mCarNavigationBar.start();
        mCarNavigationBar.showTransient(Display.DEFAULT_DISPLAY,
                new int[]{ITYPE_STATUS_BAR, ITYPE_NAVIGATION_BAR});
        assertThat(mCarNavigationBar.isStatusBarTransientShown()).isTrue();
        assertThat(mCarNavigationBar.isNavBarTransientShown()).isTrue();

        int[] insetTypes = new int[]{};
        int randomDisplay = Display.DEFAULT_DISPLAY + 10;
        mCarNavigationBar.abortTransient(randomDisplay, insetTypes);

        // The transient booleans were not cleared.
        assertThat(mCarNavigationBar.isStatusBarTransientShown()).isTrue();
        assertThat(mCarNavigationBar.isNavBarTransientShown()).isTrue();
    }

    @Test
    public void abortTransient_correctDisplayId_noInsets_transientModeNotCleared() {
        mTestableResources.addOverride(R.bool.config_enableTopNavigationBar, true);
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(true);
        mCarNavigationBar.start();
        mCarNavigationBar.showTransient(Display.DEFAULT_DISPLAY,
                new int[]{ITYPE_STATUS_BAR, ITYPE_NAVIGATION_BAR});
        assertThat(mCarNavigationBar.isStatusBarTransientShown()).isTrue();
        assertThat(mCarNavigationBar.isNavBarTransientShown()).isTrue();

        int[] insetTypes = new int[]{};
        mCarNavigationBar.abortTransient(Display.DEFAULT_DISPLAY, insetTypes);

        // The transient booleans were not cleared.
        assertThat(mCarNavigationBar.isStatusBarTransientShown()).isTrue();
        assertThat(mCarNavigationBar.isNavBarTransientShown()).isTrue();
    }

    @Test
    public void abortTransient_correctDisplayId_statusBarInset_transientModeCleared() {
        mTestableResources.addOverride(R.bool.config_enableTopNavigationBar, true);
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(true);
        mCarNavigationBar.start();
        mCarNavigationBar.showTransient(Display.DEFAULT_DISPLAY,
                new int[]{ITYPE_STATUS_BAR, ITYPE_NAVIGATION_BAR});
        assertThat(mCarNavigationBar.isStatusBarTransientShown()).isTrue();
        assertThat(mCarNavigationBar.isNavBarTransientShown()).isTrue();

        int[] insetTypes = new int[]{ITYPE_STATUS_BAR};
        mCarNavigationBar.abortTransient(Display.DEFAULT_DISPLAY, insetTypes);

        // The transient booleans were cleared.
        assertThat(mCarNavigationBar.isStatusBarTransientShown()).isFalse();
        assertThat(mCarNavigationBar.isNavBarTransientShown()).isFalse();
    }

    @Test
    public void abortTransient_correctDisplayId_navBarInset_transientModeCleared() {
        mTestableResources.addOverride(R.bool.config_enableTopNavigationBar, true);
        mTestableResources.addOverride(R.bool.config_enableBottomNavigationBar, true);
        when(mDeviceProvisionedController.isCurrentUserSetup()).thenReturn(true);
        mCarNavigationBar.start();
        mCarNavigationBar.showTransient(Display.DEFAULT_DISPLAY,
                new int[]{ITYPE_STATUS_BAR, ITYPE_NAVIGATION_BAR});
        assertThat(mCarNavigationBar.isStatusBarTransientShown()).isTrue();
        assertThat(mCarNavigationBar.isNavBarTransientShown()).isTrue();

        int[] insetTypes = new int[]{ITYPE_NAVIGATION_BAR};
        mCarNavigationBar.abortTransient(Display.DEFAULT_DISPLAY, insetTypes);

        // The transient booleans were cleared.
        assertThat(mCarNavigationBar.isStatusBarTransientShown()).isFalse();
        assertThat(mCarNavigationBar.isNavBarTransientShown()).isFalse();
    }
}
