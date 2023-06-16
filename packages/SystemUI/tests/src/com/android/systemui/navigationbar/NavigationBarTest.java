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
import static android.app.StatusBarManager.NAVIGATION_HINT_IME_SWITCHER_SHOWN;
import static android.inputmethodservice.InputMethodService.BACK_DISPOSITION_DEFAULT;
import static android.inputmethodservice.InputMethodService.IME_VISIBLE;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS;
import static android.view.WindowInsets.Type.ime;

import static com.android.internal.config.sysui.SystemUiDeviceConfigFlags.HOME_BUTTON_LONG_PRESS_DURATION_MS;
import static com.android.systemui.navigationbar.NavigationBar.NavBarActionEvent.NAVBAR_ASSIST_LONGPRESS;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_SCREEN_PINNING;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.content.Context;
import android.content.res.Resources;
import android.hardware.display.DisplayManagerGlobal;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.DeviceConfig;
import android.telecom.TelecomManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.Display;
import android.view.DisplayInfo;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewRootImpl;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityManager;
import android.view.inputmethod.InputMethodManager;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.UiEventLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.SysuiTestableContext;
import com.android.systemui.accessibility.AccessibilityButtonModeObserver;
import com.android.systemui.accessibility.AccessibilityButtonTargetsObserver;
import com.android.systemui.accessibility.SystemActions;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.keyguard.WakefulnessLifecycle;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.buttons.ButtonDispatcher;
import com.android.systemui.navigationbar.buttons.DeadZone;
import com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.recents.Recents;
import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.settings.FakeDisplayTracker;
import com.android.systemui.settings.UserContextProvider;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.shade.NotificationShadeWindowView;
import com.android.systemui.shade.ShadeController;
import com.android.systemui.shared.rotation.RotationButtonController;
import com.android.systemui.shared.system.TaskStackChangeListeners;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationRemoteInputManager;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.LightBarTransitionsController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.DeviceProvisionedController;
import com.android.systemui.statusbar.policy.KeyguardStateController;
import com.android.systemui.util.DeviceConfigProxyFake;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.utils.leaks.LeakCheckedTest;
import com.android.wm.shell.back.BackAnimation;
import com.android.wm.shell.pip.Pip;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;
import java.util.concurrent.Executor;

@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
@SmallTest
public class NavigationBarTest extends SysuiTestCase {
    private static final int EXTERNAL_DISPLAY_ID = 2;

    private NavigationBar mNavigationBar;
    private NavigationBar mExternalDisplayNavigationBar;

    private SysuiTestableContext mSysuiTestableContextExternal;
    @Mock
    NavigationBarView mNavigationBarView;
    @Mock
    NavigationBarFrame mNavigationBarFrame;
    @Mock
    ButtonDispatcher mHomeButton;
    @Mock
    ButtonDispatcher mRecentsButton;
    @Mock
    ButtonDispatcher mAccessibilityButton;
    @Mock
    ButtonDispatcher mImeSwitchButton;
    @Mock
    ButtonDispatcher mBackButton;
    @Mock
    NavigationBarTransitions mNavigationBarTransitions;
    @Mock
    RotationButtonController mRotationButtonController;
    @Mock
    LightBarTransitionsController mLightBarTransitionsController;
    @Mock
    private SystemActions mSystemActions;
    @Mock
    private OverviewProxyService mOverviewProxyService;
    @Mock
    private StatusBarStateController mStatusBarStateController;
    @Mock
    private StatusBarKeyguardViewManager mStatusBarKeyguardViewManager;
    @Mock
    private NavigationModeController mNavigationModeController;
    @Mock
    private CommandQueue mCommandQueue;
    private SysUiState mMockSysUiState;
    @Mock
    private Handler mHandler;
    @Mock
    private UserTracker mUserTracker;
    @Mock
    private UiEventLogger mUiEventLogger;
    @Mock
    private ViewTreeObserver mViewTreeObserver;
    NavBarHelper mNavBarHelper;
    @Mock
    private LightBarController mLightBarController;
    @Mock
    private LightBarController.Factory mLightBarcontrollerFactory;
    @Mock
    private AutoHideController mAutoHideController;
    @Mock
    private AutoHideController.Factory mAutoHideControllerFactory;
    @Mock
    private WindowManager mWindowManager;
    @Mock
    private TelecomManager mTelecomManager;
    @Mock
    private InputMethodManager mInputMethodManager;
    @Mock
    private AssistManager mAssistManager;
    @Mock
    private DeadZone mDeadZone;
    @Mock
    private CentralSurfaces mCentralSurfaces;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private UserContextProvider mUserContextProvider;
    @Mock
    private WakefulnessLifecycle mWakefulnessLifecycle;
    @Mock
    private Resources mResources;
    @Mock
    private ViewRootImpl mViewRootImpl;
    @Mock
    private EdgeBackGestureHandler.Factory mEdgeBackGestureHandlerFactory;
    @Mock
    private EdgeBackGestureHandler mEdgeBackGestureHandler;
    @Mock
    private NotificationShadeWindowController mNotificationShadeWindowController;
    private FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());
    private DeviceConfigProxyFake mDeviceConfigProxyFake = new DeviceConfigProxyFake();
    private TaskStackChangeListeners mTaskStackChangeListeners =
            TaskStackChangeListeners.getTestInstance();

    @Rule
    public final LeakCheckedTest.SysuiLeakCheck mLeakCheck = new LeakCheckedTest.SysuiLeakCheck();

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mLightBarcontrollerFactory.create(any(Context.class))).thenReturn(mLightBarController);
        when(mAutoHideControllerFactory.create(any(Context.class))).thenReturn(mAutoHideController);
        when(mNavigationBarView.getHomeButton()).thenReturn(mHomeButton);
        when(mNavigationBarView.getRecentsButton()).thenReturn(mRecentsButton);
        when(mNavigationBarView.getAccessibilityButton()).thenReturn(mAccessibilityButton);
        when(mNavigationBarView.getImeSwitchButton()).thenReturn(mImeSwitchButton);
        when(mNavigationBarView.getBackButton()).thenReturn(mBackButton);
        when(mNavigationBarView.getRotationButtonController())
                .thenReturn(mRotationButtonController);
        when(mNavigationBarTransitions.getLightTransitionsController())
                .thenReturn(mLightBarTransitionsController);
        when(mStatusBarKeyguardViewManager.isNavBarVisible()).thenReturn(true);
        when(mNavigationBarView.getViewTreeObserver()).thenReturn(mViewTreeObserver);
        when(mUserContextProvider.createCurrentUserContext(any(Context.class)))
                .thenReturn(mContext);
        when(mNavigationBarView.getResources()).thenReturn(mResources);
        when(mNavigationBarView.getViewRootImpl()).thenReturn(mViewRootImpl);
        when(mEdgeBackGestureHandlerFactory.create(any())).thenReturn(mEdgeBackGestureHandler);
        setupSysuiDependency();
        // This class inflates views that call Dependency.get, thus these injections are still
        // necessary.
        mDependency.injectTestDependency(AssistManager.class, mAssistManager);
        mDependency.injectMockDependency(KeyguardStateController.class);
        mDependency.injectTestDependency(StatusBarStateController.class, mStatusBarStateController);
        mDependency.injectMockDependency(NavigationBarController.class);
        mDependency.injectTestDependency(OverviewProxyService.class, mOverviewProxyService);
        mDependency.injectTestDependency(NavigationModeController.class, mNavigationModeController);
        TestableLooper.get(this).runWithLooper(() -> {
            mNavBarHelper = spy(new NavBarHelper(mContext, mock(AccessibilityManager.class),
                    mock(AccessibilityButtonModeObserver.class),
                    mock(AccessibilityButtonTargetsObserver.class),
                    mSystemActions, mOverviewProxyService,
                    () -> mock(AssistManager.class), () -> Optional.of(mCentralSurfaces),
                    mKeyguardStateController, mock(NavigationModeController.class),
                    mEdgeBackGestureHandlerFactory, mock(IWindowManager.class),
                    mock(UserTracker.class), mock(DisplayTracker.class),
                    mNotificationShadeWindowController, mock(DumpManager.class),
                    mock(CommandQueue.class)));
            mNavigationBar = createNavBar(mContext);
            mExternalDisplayNavigationBar = createNavBar(mSysuiTestableContextExternal);
        });
    }

    private void setupSysuiDependency() {
        Display display = new Display(DisplayManagerGlobal.getInstance(), EXTERNAL_DISPLAY_ID,
                new DisplayInfo(), DEFAULT_DISPLAY_ADJUSTMENTS);
        mSysuiTestableContextExternal = (SysuiTestableContext) getContext().createDisplayContext(
                display);

        Display defaultDisplay = mContext.getDisplay();
        when(mWindowManager.getDefaultDisplay()).thenReturn(defaultDisplay);
        WindowMetrics metrics = mContext.getSystemService(WindowManager.class)
                .getMaximumWindowMetrics();
        when(mWindowManager.getMaximumWindowMetrics()).thenReturn(metrics);
        WindowMetrics currentWindowMetrics = mContext.getSystemService(WindowManager.class)
                .getCurrentWindowMetrics();
        when(mWindowManager.getCurrentWindowMetrics()).thenReturn(currentWindowMetrics);
        doNothing().when(mWindowManager).addView(any(), any());
        doNothing().when(mWindowManager).removeViewImmediate(any());
        mMockSysUiState = mock(SysUiState.class);
        when(mMockSysUiState.setFlag(anyInt(), anyBoolean())).thenReturn(mMockSysUiState);

        mContext.addMockSystemService(WindowManager.class, mWindowManager);
        mSysuiTestableContextExternal.addMockSystemService(WindowManager.class, mWindowManager);
    }

    @Test
    public void testHomeLongPress() {
        mNavigationBar.init();
        mNavigationBar.onViewAttached();
        mNavigationBar.onHomeLongClick(mNavigationBar.getView());

        verify(mUiEventLogger, times(1)).log(NAVBAR_ASSIST_LONGPRESS);
    }

    @Test
    public void testHomeLongPressWithCustomDuration() throws Exception {
        mDeviceConfigProxyFake.setProperty(
                DeviceConfig.NAMESPACE_SYSTEMUI,
                HOME_BUTTON_LONG_PRESS_DURATION_MS,
                "100",
                false);
        when(mNavBarHelper.getLongPressHomeEnabled()).thenReturn(true);
        mNavigationBar.init();
        mNavigationBar.onViewAttached();

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
    public void testRegisteredWithUserTracker() {
        mNavigationBar.init();
        mNavigationBar.onViewAttached();
        verify(mUserTracker).addCallback(any(UserTracker.Callback.class), any(Executor.class));
    }

    @Test
    public void testSetImeWindowStatusWhenImeSwitchOnDisplay() {
        // Create default & external NavBar fragment.
        NavigationBar defaultNavBar = mNavigationBar;
        NavigationBar externalNavBar = mExternalDisplayNavigationBar;
        NotificationShadeWindowView mockShadeWindowView = mock(NotificationShadeWindowView.class);
        WindowInsets windowInsets = new WindowInsets.Builder().setVisible(ime(), false).build();
        doReturn(windowInsets).when(mockShadeWindowView).getRootWindowInsets();
        doReturn(true).when(mockShadeWindowView).isAttachedToWindow();
        doNothing().when(defaultNavBar).checkNavBarModes();
        doNothing().when(externalNavBar).checkNavBarModes();
        defaultNavBar.init();
        externalNavBar.init();

        defaultNavBar.setImeWindowStatus(DEFAULT_DISPLAY, null, IME_VISIBLE,
                BACK_DISPOSITION_DEFAULT, true);

        // Verify IME window state will be updated in default NavBar & external NavBar state reset.
        assertEquals(NAVIGATION_HINT_BACK_ALT | NAVIGATION_HINT_IME_SHOWN
                        | NAVIGATION_HINT_IME_SWITCHER_SHOWN,
                defaultNavBar.getNavigationIconHints());
        assertFalse((externalNavBar.getNavigationIconHints() & NAVIGATION_HINT_BACK_ALT) != 0);
        assertFalse((externalNavBar.getNavigationIconHints() & NAVIGATION_HINT_IME_SHOWN) != 0);
        assertFalse((externalNavBar.getNavigationIconHints() & NAVIGATION_HINT_IME_SWITCHER_SHOWN)
                != 0);

        externalNavBar.setImeWindowStatus(EXTERNAL_DISPLAY_ID, null, IME_VISIBLE,
                BACK_DISPOSITION_DEFAULT, true);
        defaultNavBar.setImeWindowStatus(
                DEFAULT_DISPLAY, null, 0 /* vis */, BACK_DISPOSITION_DEFAULT, false);
        // Verify IME window state will be updated in external NavBar & default NavBar state reset.
        assertEquals(NAVIGATION_HINT_BACK_ALT | NAVIGATION_HINT_IME_SHOWN
                        | NAVIGATION_HINT_IME_SWITCHER_SHOWN,
                externalNavBar.getNavigationIconHints());
        assertFalse((defaultNavBar.getNavigationIconHints() & NAVIGATION_HINT_BACK_ALT) != 0);
        assertFalse((defaultNavBar.getNavigationIconHints() & NAVIGATION_HINT_IME_SHOWN) != 0);
        assertFalse((defaultNavBar.getNavigationIconHints() & NAVIGATION_HINT_IME_SWITCHER_SHOWN)
                != 0);
    }

    @Test
    public void testSetImeWindowStatusWhenKeyguardLockingAndImeInsetsChange() {
        NotificationShadeWindowView mockShadeWindowView = mock(NotificationShadeWindowView.class);
        doReturn(mockShadeWindowView).when(mNotificationShadeWindowController).getWindowRootView();
        doReturn(true).when(mockShadeWindowView).isAttachedToWindow();
        doNothing().when(mNavigationBar).checkNavBarModes();
        mNavigationBar.init();
        WindowInsets windowInsets = new WindowInsets.Builder().setVisible(ime(), false).build();
        doReturn(windowInsets).when(mockShadeWindowView).getRootWindowInsets();

        // Verify navbar altered back icon when an app is showing IME
        mNavigationBar.setImeWindowStatus(DEFAULT_DISPLAY, null, IME_VISIBLE,
                BACK_DISPOSITION_DEFAULT, true);
        assertTrue((mNavigationBar.getNavigationIconHints() & NAVIGATION_HINT_BACK_ALT) != 0);
        assertTrue((mNavigationBar.getNavigationIconHints() & NAVIGATION_HINT_IME_SHOWN) != 0);
        assertTrue((mNavigationBar.getNavigationIconHints() & NAVIGATION_HINT_IME_SWITCHER_SHOWN)
                != 0);

        // Verify navbar didn't alter and showing back icon when the keyguard is showing without
        // requesting IME insets visible.
        doReturn(true).when(mKeyguardStateController).isShowing();
        mNavigationBar.setImeWindowStatus(DEFAULT_DISPLAY, null, IME_VISIBLE,
                BACK_DISPOSITION_DEFAULT, true);
        assertFalse((mNavigationBar.getNavigationIconHints() & NAVIGATION_HINT_BACK_ALT) != 0);
        assertFalse((mNavigationBar.getNavigationIconHints() & NAVIGATION_HINT_IME_SHOWN) != 0);
        assertFalse((mNavigationBar.getNavigationIconHints() & NAVIGATION_HINT_IME_SWITCHER_SHOWN)
                != 0);

        // Verify navbar altered and showing back icon when the keyguard is showing and
        // requesting IME insets visible.
        windowInsets = new WindowInsets.Builder().setVisible(ime(), true).build();
        doReturn(windowInsets).when(mockShadeWindowView).getRootWindowInsets();
        mNavigationBar.setImeWindowStatus(DEFAULT_DISPLAY, null, IME_VISIBLE,
                BACK_DISPOSITION_DEFAULT, true);
        assertTrue((mNavigationBar.getNavigationIconHints() & NAVIGATION_HINT_BACK_ALT) != 0);
        assertTrue((mNavigationBar.getNavigationIconHints() & NAVIGATION_HINT_IME_SHOWN) != 0);
        assertTrue((mNavigationBar.getNavigationIconHints() & NAVIGATION_HINT_IME_SWITCHER_SHOWN)
                != 0);
    }

    @Test
    public void testA11yEventAfterDetach() {
        mNavigationBar.init();
        mNavigationBar.onViewAttached();
        verify(mNavBarHelper).registerNavTaskStateUpdater(any(
                NavBarHelper.NavbarTaskbarStateUpdater.class));
        mNavigationBar.onViewDetached();
        verify(mNavBarHelper).removeNavTaskStateUpdater(any(
                NavBarHelper.NavbarTaskbarStateUpdater.class));

        // Should be safe even though the internal view is now null.
        mNavigationBar.updateAccessibilityStateFlags();
    }

    @Test
    public void testCreateView_initiallyVisible_viewIsVisible() {
        when(mStatusBarKeyguardViewManager.isNavBarVisible()).thenReturn(true);
        mNavigationBar.init();
        mNavigationBar.onViewAttached();

        verify(mNavigationBarView).setVisibility(View.VISIBLE);
    }

    @Test
    public void testCreateView_initiallyNotVisible_viewIsNotVisible() {
        when(mStatusBarKeyguardViewManager.isNavBarVisible()).thenReturn(false);
        mNavigationBar.init();
        mNavigationBar.onViewAttached();

        verify(mNavigationBarView).setVisibility(View.INVISIBLE);
    }

    @Test
    public void testOnInit_readCurrentSysuiState() {
        mNavigationBar.init();
        verify(mNavBarHelper, times(1)).getCurrentSysuiState();
    }

    @Test
    public void testScreenPinningEnabled_updatesSysuiState() {
        mNavigationBar.init();
        mTaskStackChangeListeners.getListenerImpl().onLockTaskModeChanged(
                ActivityManager.LOCK_TASK_MODE_PINNED);
        verify(mMockSysUiState).setFlag(eq(SYSUI_STATE_SCREEN_PINNING), eq(true));
    }

    private NavigationBar createNavBar(Context context) {
        DeviceProvisionedController deviceProvisionedController =
                mock(DeviceProvisionedController.class);
        when(deviceProvisionedController.isDeviceProvisioned()).thenReturn(true);
        return spy(new NavigationBar(
                mNavigationBarView,
                mNavigationBarFrame,
                null,
                context,
                mWindowManager,
                () -> mAssistManager,
                mock(AccessibilityManager.class),
                deviceProvisionedController,
                new MetricsLogger(),
                mOverviewProxyService,
                mNavigationModeController,
                mStatusBarStateController,
                mStatusBarKeyguardViewManager,
                mMockSysUiState,
                mUserTracker,
                mCommandQueue,
                Optional.of(mock(Pip.class)),
                Optional.of(mock(Recents.class)),
                () -> Optional.of(mCentralSurfaces),
                mock(ShadeController.class),
                mock(NotificationRemoteInputManager.class),
                mock(NotificationShadeDepthController.class),
                mHandler,
                mFakeExecutor,
                mFakeExecutor,
                mUiEventLogger,
                mNavBarHelper,
                mLightBarController,
                mLightBarcontrollerFactory,
                mAutoHideController,
                mAutoHideControllerFactory,
                Optional.of(mTelecomManager),
                mInputMethodManager,
                mDeadZone,
                mDeviceConfigProxyFake,
                mNavigationBarTransitions,
                Optional.of(mock(BackAnimation.class)),
                mUserContextProvider,
                mWakefulnessLifecycle,
                mTaskStackChangeListeners,
                new FakeDisplayTracker(mContext)));
    }

    private void processAllMessages() {
        TestableLooper.get(this).processAllMessages();
    }
}
