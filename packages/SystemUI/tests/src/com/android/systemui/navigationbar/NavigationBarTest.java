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

package com.android.systemui.navigationbar;

import static android.app.StatusBarManager.NAVIGATION_HINT_BACK_ALT;
import static android.app.StatusBarManager.NAVIGATION_HINT_IME_SHOWN;
import static android.inputmethodservice.InputMethodService.BACK_DISPOSITION_DEFAULT;
import static android.inputmethodservice.InputMethodService.IME_VISIBLE;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.HOME_BUTTON_LONG_PRESS_DURATION_MS;
import static com.android.systemui.navigationbar.NavigationBar.NavBarActionEvent.NAVBAR_ASSIST_LONGPRESS;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityManager;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.SysuiTestableContext;
import com.android.systemui.accessibility.AccessibilityButtonModeObserver;
import com.android.systemui.accessibility.SystemActions;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.recents.Recents;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.phone.ShadeController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.utils.leaks.LeakCheckedTest;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;
import com.android.wm.shell.pip.Pip;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
public class NavigationBarTest extends SysuiTestCase {
    private static final int EXTERNAL_DISPLAY_ID = 2;

    private NavigationBar mNavigationBar;
    private NavigationBar mExternalDisplayNavigationBar;

    private SysuiTestableContext mSysuiTestableContextExternal;
    private OverviewProxyService mOverviewProxyService;
    private CommandQueue mCommandQueue;
    private SysUiState mMockSysUiState;
    @Mock
    private Handler mHandler;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private UiEventLogger mUiEventLogger;

    @Rule
    public final LeakCheckedTest.SysuiLeakCheck mLeakCheck = new LeakCheckedTest.SysuiLeakCheck();
    private AccessibilityManagerWrapper mAccessibilityWrapper =
            new AccessibilityManagerWrapper(mContext);

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        mCommandQueue = new CommandQueue(mContext);
        setupSysuiDependency();
        mDependency.injectMockDependency(AssistManager.class);
        mDependency.injectMockDependency(KeyguardStateController.class);
        mDependency.injectMockDependency(StatusBarStateController.class);
        mDependency.injectMockDependency(NavigationBarController.class);
        mOverviewProxyService = mDependency.injectMockDependency(OverviewProxyService.class);
        mDependency.injectMockDependency(EdgeBackGestureHandler.class);
        TestableLooper.get(this).runWithLooper(() -> {
            mNavigationBar = createNavBar(mContext);
            mExternalDisplayNavigationBar = createNavBar(mSysuiTestableContextExternal);
        });
    }

    @After
    public void tearDown() throws Exception {
        DeviceConfig.resetToDefaults(
                Settings.RESET_MODE_PACKAGE_DEFAULTS, DeviceConfig.NAMESPACE_SYSTEMUI);
    }

    private void setupSysuiDependency() {
        Display display = new Display(DisplayManagerGlobal.getInstance(), EXTERNAL_DISPLAY_ID,
                new DisplayInfo(), DEFAULT_DISPLAY_ADJUSTMENTS);
        mSysuiTestableContextExternal = (SysuiTestableContext) getContext().createDisplayContext(
                display);

        WindowManager windowManager = mock(WindowManager.class);
        Display defaultDisplay = mContext.getSystemService(WindowManager.class).getDefaultDisplay();
        when(windowManager.getDefaultDisplay()).thenReturn(
                defaultDisplay);
        WindowMetrics metrics = mContext.getSystemService(WindowManager.class)
                .getMaximumWindowMetrics();
        when(windowManager.getMaximumWindowMetrics()).thenReturn(metrics);
        doNothing().when(windowManager).addView(any(), any());
        mContext.addMockSystemService(Context.WINDOW_SERVICE, windowManager);
        mSysuiTestableContextExternal.addMockSystemService(Context.WINDOW_SERVICE, windowManager);

        mDependency.injectTestDependency(Dependency.BG_LOOPER, Looper.getMainLooper());
        mDependency.injectTestDependency(AccessibilityManagerWrapper.class, mAccessibilityWrapper);

        mMockSysUiState = mock(SysUiState.class);
        when(mMockSysUiState.setFlag(anyInt(), anyBoolean())).thenReturn(mMockSysUiState);
    }

    @Test
    public void testHomeLongPress() {
        mNavigationBar.onViewAttachedToWindow(mNavigationBar.createView(null));
        mNavigationBar.onHomeLongClick(mNavigationBar.getView());

        verify(mUiEventLogger, times(1)).log(NAVBAR_ASSIST_LONGPRESS);
    }

    @Test
    public void testHomeLongPressWithCustomDuration() throws Exception {
        DeviceConfig.setProperties(
                new DeviceConfig.Properties.Builder(DeviceConfig.NAMESPACE_SYSTEMUI)
                    .setLong(HOME_BUTTON_LONG_PRESS_DURATION_MS, 100)
                    .build());
        mNavigationBar.onViewAttachedToWindow(mNavigationBar.createView(null));

        mNavigationBar.onHomeTouch(mNavigationBar.getView(), MotionEvent.obtain(
                /*downTime=*/SystemClock.uptimeMillis(),
                /*eventTime=*/SystemClock.uptimeMillis(),
                /*action=*/MotionEvent.ACTION_DOWN,
                0, 0, 0
        ));
        verify(mHandler, times(1)).postDelayed(any(), eq(100L));

        mNavigationBar.onHomeTouch(mNavigationBar.getView(), MotionEvent.obtain(
                /*downTime=*/SystemClock.uptimeMillis(),
                /*eventTime=*/SystemClock.uptimeMillis(),
                /*action=*/MotionEvent.ACTION_UP,
                0, 0, 0
        ));

        verify(mHandler, times(1)).removeCallbacks(any());
    }

    @Test
    public void testRegisteredWithDispatcher() {
        mNavigationBar.onViewAttachedToWindow(mNavigationBar.createView(null));
        verify(mBroadcastDispatcher).registerReceiverWithHandler(
                any(BroadcastReceiver.class),
                any(IntentFilter.class),
                any(Handler.class),
                any(UserHandle.class));
    }

    @Test
    public void testSetImeWindowStatusWhenImeSwitchOnDisplay() {
        // Create default & external NavBar fragment.
        NavigationBar defaultNavBar = mNavigationBar;
        NavigationBar externalNavBar = mExternalDisplayNavigationBar;
        doNothing().when(defaultNavBar).checkNavBarModes();
        doNothing().when(externalNavBar).checkNavBarModes();
        defaultNavBar.createView(null);
        externalNavBar.createView(null);

        // Set IME window status for default NavBar.
        mCommandQueue.setImeWindowStatus(DEFAULT_DISPLAY, null, IME_VISIBLE,
                BACK_DISPOSITION_DEFAULT, true, false);
        processAllMessages();

        // Verify IME window state will be updated in default NavBar & external NavBar state reset.
        assertEquals(NAVIGATION_HINT_BACK_ALT | NAVIGATION_HINT_IME_SHOWN,
                defaultNavBar.getNavigationIconHints());
        assertFalse((externalNavBar.getNavigationIconHints() & NAVIGATION_HINT_BACK_ALT) != 0);
        assertFalse((externalNavBar.getNavigationIconHints() & NAVIGATION_HINT_IME_SHOWN) != 0);

        // Set IME window status for external NavBar.
        mCommandQueue.setImeWindowStatus(EXTERNAL_DISPLAY_ID, null,
                IME_VISIBLE, BACK_DISPOSITION_DEFAULT, true, false);
        processAllMessages();

        // Verify IME window state will be updated in external NavBar & default NavBar state reset.
        assertEquals(NAVIGATION_HINT_BACK_ALT | NAVIGATION_HINT_IME_SHOWN,
                externalNavBar.getNavigationIconHints());
        assertFalse((defaultNavBar.getNavigationIconHints() & NAVIGATION_HINT_BACK_ALT) != 0);
        assertFalse((defaultNavBar.getNavigationIconHints() & NAVIGATION_HINT_IME_SHOWN) != 0);
    }

    private NavigationBar createNavBar(Context context) {
        DeviceProvisionedController deviceProvisionedController =
                mock(DeviceProvisionedController.class);
        when(deviceProvisionedController.isDeviceProvisioned()).thenReturn(true);
        assertNotNull(mAccessibilityWrapper);
        return spy(new NavigationBar(context,
                mock(WindowManager.class),
                () -> mock(AssistManager.class),
                mock(AccessibilityManager.class),
                context.getDisplayId() == DEFAULT_DISPLAY ? mAccessibilityWrapper
                        : mock(AccessibilityManagerWrapper.class),
                deviceProvisionedController,
                new MetricsLogger(),
                mOverviewProxyService,
                mock(NavigationModeController.class),
                mock(AccessibilityButtonModeObserver.class),
                mock(StatusBarStateController.class),
                mMockSysUiState,
                mBroadcastDispatcher,
                mCommandQueue,
                Optional.of(mock(Pip.class)),
                Optional.of(mock(LegacySplitScreen.class)),
                Optional.of(mock(Recents.class)),
                () -> mock(StatusBar.class),
                mock(ShadeController.class),
                mock(NotificationRemoteInputManager.class),
                mock(NotificationShadeDepthController.class),
                mock(SystemActions.class),
                mHandler,
                mock(NavigationBarOverlayController.class),
                mUiEventLogger,
                mock(UserTracker.class)));
    }

    private void processAllMessages() {
        TestableLooper.get(this).processAllMessages();
    }
}
