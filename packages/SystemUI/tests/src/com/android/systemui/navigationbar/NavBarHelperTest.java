/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static android.app.StatusBarManager.WINDOW_NAVIGATION_BAR;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU;
import static android.provider.Settings.Secure.ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR;

import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_CLICKABLE;
import static com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.res.Configuration;
import android.os.Handler;
import android.view.IWindowManager;
import android.view.accessibility.AccessibilityManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.accessibility.common.ShortcutConstants.UserShortcutType;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.accessibility.AccessibilityButtonModeObserver;
import com.android.systemui.accessibility.AccessibilityButtonTargetsObserver;
import com.android.systemui.accessibility.SystemActions;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.navigationbar.gestural.EdgeBackGestureHandler;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.settings.DisplayTracker;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.CentralSurfaces;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.policy.FakeConfigurationController;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import dagger.Lazy;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Tests for {@link NavBarHelper}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class NavBarHelperTest extends SysuiTestCase {

    private static final int DISPLAY_ID = 0;
    private static final int WINDOW = WINDOW_NAVIGATION_BAR;
    private static final int STATE_ID = 0;

    @Mock
    AccessibilityManager mAccessibilityManager;
    @Mock
    AccessibilityButtonModeObserver mAccessibilityButtonModeObserver;
    @Mock
    AccessibilityButtonTargetsObserver mAccessibilityButtonTargetObserver;
    @Mock
    SystemActions mSystemActions;
    @Mock
    OverviewProxyService mOverviewProxyService;
    @Mock
    Lazy<AssistManager> mAssistManagerLazy;
    @Mock
    AssistManager mAssistManager;
    @Mock
    NavigationModeController mNavigationModeController;
    @Mock
    UserTracker mUserTracker;
    @Mock
    ComponentName mAssistantComponent;
    @Mock
    DumpManager mDumpManager;
    @Mock
    NavBarHelper.NavbarTaskbarStateUpdater mNavbarTaskbarStateUpdater;
    @Mock
    CommandQueue mCommandQueue;
    @Mock
    IWindowManager mWm;
    @Mock
    DisplayTracker mDisplayTracker;
    @Mock
    EdgeBackGestureHandler mEdgeBackGestureHandler;
    @Mock
    EdgeBackGestureHandler.Factory mEdgeBackGestureHandlerFactory;
    @Mock
    NotificationShadeWindowController mNotificationShadeWindowController;
    @Mock
    Handler mBgHandler;

    @Captor ArgumentCaptor<Runnable> mRunnableArgumentCaptor;
    ConfigurationController mConfigurationController = new FakeConfigurationController();

    private AccessibilityManager.AccessibilityServicesStateChangeListener
            mAccessibilityServicesStateChangeListener;

    private static final long ACCESSIBILITY_BUTTON_CLICKABLE_STATE =
            SYSUI_STATE_A11Y_BUTTON_CLICKABLE | SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE;
    private NavBarHelper mNavBarHelper;

    private final Executor mSynchronousExecutor = runnable -> runnable.run();

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mAssistManagerLazy.get()).thenReturn(mAssistManager);
        when(mAssistManager.getAssistInfoForUser(anyInt())).thenReturn(mAssistantComponent);
        when(mUserTracker.getUserId()).thenReturn(1);
        when(mDisplayTracker.getDefaultDisplayId()).thenReturn(0);
        when(mEdgeBackGestureHandlerFactory.create(any())).thenReturn(mEdgeBackGestureHandler);

        doAnswer((invocation) -> mAccessibilityServicesStateChangeListener =
                invocation.getArgument(0)).when(
                mAccessibilityManager).addAccessibilityServicesStateChangeListener(any());
        mNavBarHelper = new NavBarHelper(mContext, mAccessibilityManager,
                mAccessibilityButtonModeObserver, mAccessibilityButtonTargetObserver,
                mSystemActions, mOverviewProxyService, mAssistManagerLazy,
                () -> Optional.of(mock(CentralSurfaces.class)), mock(KeyguardStateController.class),
                mNavigationModeController, mEdgeBackGestureHandlerFactory, mWm, mUserTracker,
                mDisplayTracker, mNotificationShadeWindowController, mConfigurationController,
                mDumpManager, mCommandQueue, mSynchronousExecutor, mBgHandler);
    }

    @Test
    public void registerListenersInCtor() {
        verify(mNavigationModeController, times(1)).addListener(mNavBarHelper);
        verify(mOverviewProxyService, times(1)).addCallback(mNavBarHelper);
        verify(mCommandQueue, times(1)).addCallback(any());
    }

    @Test
    public void testSetupBarsRegistersListeners() throws Exception {
        mNavBarHelper.registerNavTaskStateUpdater(mNavbarTaskbarStateUpdater);
        verify(mAccessibilityButtonModeObserver, times(1)).addListener(mNavBarHelper);
        verify(mAccessibilityButtonTargetObserver, times(1)).addListener(mNavBarHelper);
        verify(mAccessibilityManager, times(1)).addAccessibilityServicesStateChangeListener(
                mNavBarHelper);
        verify(mAssistManager, times(1)).getAssistInfoForUser(anyInt());
        verify(mWm, times(1)).watchRotation(any(), anyInt());
        verify(mWm, times(1)).registerWallpaperVisibilityListener(any(), anyInt());
        verify(mEdgeBackGestureHandler, times(1)).onNavBarAttached();
    }

    @Test
    public void testCleanupBarsUnregistersListeners() throws Exception {
        mNavBarHelper.registerNavTaskStateUpdater(mNavbarTaskbarStateUpdater);
        mNavBarHelper.removeNavTaskStateUpdater(mNavbarTaskbarStateUpdater);
        verify(mAccessibilityButtonModeObserver, times(1)).removeListener(mNavBarHelper);
        verify(mAccessibilityButtonTargetObserver, times(1)).removeListener(mNavBarHelper);
        verify(mAccessibilityManager, times(1)).removeAccessibilityServicesStateChangeListener(
                mNavBarHelper);
        verify(mWm, times(1)).removeRotationWatcher(any());
        verify(mWm, times(1)).unregisterWallpaperVisibilityListener(any(), anyInt());
        verify(mEdgeBackGestureHandler, times(1)).onNavBarDetached();
    }

    @Test
    public void replacingBarsHint() {
        mNavBarHelper.registerNavTaskStateUpdater(mNavbarTaskbarStateUpdater);
        mNavBarHelper.setTogglingNavbarTaskbar(true);
        mNavBarHelper.removeNavTaskStateUpdater(mNavbarTaskbarStateUpdater);
        mNavBarHelper.registerNavTaskStateUpdater(mNavbarTaskbarStateUpdater);
        mNavBarHelper.setTogglingNavbarTaskbar(false);
        // Use any state in cleanup to verify it was not called
        verify(mAccessibilityButtonModeObserver, times(0)).removeListener(mNavBarHelper);
    }

    @Test
    public void callbacksFiredWhenRegistering() {
        mNavBarHelper.registerNavTaskStateUpdater(mNavbarTaskbarStateUpdater);
        verify(mNavbarTaskbarStateUpdater, times(1))
                .updateAccessibilityServicesState();
        verify(mNavbarTaskbarStateUpdater, times(1))
                .updateAssistantAvailable(anyBoolean(), anyBoolean());
        verify(mBgHandler).post(mRunnableArgumentCaptor.capture());
        mRunnableArgumentCaptor.getValue().run();
        verify(mNavbarTaskbarStateUpdater, times(1))
                .updateRotationWatcherState(anyInt(), anyBoolean());
        verify(mNavbarTaskbarStateUpdater, times(1))
                .updateWallpaperVisibility(anyBoolean(), anyInt());
    }

    @Test
    public void assistantCallbacksFiredAfterConnecting() {
        // 1st set of callbacks get called when registering
        mNavBarHelper.registerNavTaskStateUpdater(mNavbarTaskbarStateUpdater);

        mNavBarHelper.onConnectionChanged(false);
        // assert no more callbacks fired
        verify(mNavbarTaskbarStateUpdater, times(1))
                .updateAccessibilityServicesState();
        verify(mNavbarTaskbarStateUpdater, times(1))
                .updateAssistantAvailable(anyBoolean(), anyBoolean());

        mNavBarHelper.onConnectionChanged(true);
        // assert no more callbacks fired
        verify(mNavbarTaskbarStateUpdater, times(1))
                .updateAccessibilityServicesState();
        verify(mNavbarTaskbarStateUpdater, times(2))
                .updateAssistantAvailable(anyBoolean(), anyBoolean());
    }

    @Test
    public void a11yCallbacksFiredAfterModeChange() {
        // 1st set of callbacks get called when registering
        mNavBarHelper.registerNavTaskStateUpdater(mNavbarTaskbarStateUpdater);

        mNavBarHelper.onAccessibilityButtonModeChanged(0);
        verify(mNavbarTaskbarStateUpdater, times(2))
                .updateAccessibilityServicesState();
        verify(mNavbarTaskbarStateUpdater, times(1))
                .updateAssistantAvailable(anyBoolean(), anyBoolean());
    }

    @Test
    public void assistantCallbacksFiredAfterNavModeChange() {
        // 1st set of callbacks get called when registering
        mNavBarHelper.registerNavTaskStateUpdater(mNavbarTaskbarStateUpdater);

        mNavBarHelper.onNavigationModeChanged(0);
        verify(mNavbarTaskbarStateUpdater, times(1))
                .updateAccessibilityServicesState();
        verify(mNavbarTaskbarStateUpdater, times(2))
                .updateAssistantAvailable(anyBoolean(), anyBoolean());
    }

    @Test
    public void removeListenerNoCallbacksFired() {
        // 1st set of callbacks get called when registering
        mNavBarHelper.registerNavTaskStateUpdater(mNavbarTaskbarStateUpdater);

        // Remove listener
        mNavBarHelper.removeNavTaskStateUpdater(mNavbarTaskbarStateUpdater);

        // Would have fired 2nd callback if not removed
        mNavBarHelper.onAccessibilityButtonModeChanged(0);

        // assert no more callbacks fired
        verify(mNavbarTaskbarStateUpdater, times(1))
                .updateAccessibilityServicesState();
        verify(mNavbarTaskbarStateUpdater, times(1))
                .updateAssistantAvailable(anyBoolean(), anyBoolean());
    }

    @Test
    public void initNavBarHelper_buttonModeNavBar_a11yButtonClickableState() {
        when(mAccessibilityManager.getAccessibilityShortcutTargets(UserShortcutType.SOFTWARE))
                .thenReturn(createFakeShortcutTargets());

        mNavBarHelper.registerNavTaskStateUpdater(mNavbarTaskbarStateUpdater);

        assertThat(mNavBarHelper.getA11yButtonState()).isEqualTo(
                ACCESSIBILITY_BUTTON_CLICKABLE_STATE);
    }

    @Test
    public void initAccessibilityStateWithFloatingMenuModeAndTargets_disableClickableState() {
        when(mAccessibilityButtonModeObserver.getCurrentAccessibilityButtonMode()).thenReturn(
                ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU);

        mNavBarHelper.registerNavTaskStateUpdater(mNavbarTaskbarStateUpdater);

        assertThat(mNavBarHelper.getA11yButtonState()).isEqualTo(/* disable_clickable_state */ 0);
    }

    @Test
    public void onA11yServicesStateChangedWithMultipleServices_a11yButtonClickableState() {
        mNavBarHelper.registerNavTaskStateUpdater(mNavbarTaskbarStateUpdater);
        when(mAccessibilityButtonModeObserver.getCurrentAccessibilityButtonMode()).thenReturn(
                ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR);

        when(mAccessibilityManager.getAccessibilityShortcutTargets(UserShortcutType.SOFTWARE))
                .thenReturn(createFakeShortcutTargets());
        mAccessibilityServicesStateChangeListener.onAccessibilityServicesStateChanged(
                mAccessibilityManager);

        assertThat(mNavBarHelper.getA11yButtonState()).isEqualTo(
                ACCESSIBILITY_BUTTON_CLICKABLE_STATE);
    }

    @Test
    public void saveMostRecentSysuiState() {
        mNavBarHelper.setWindowState(DISPLAY_ID, WINDOW, STATE_ID);
        NavBarHelper.CurrentSysuiState state1 = mNavBarHelper.getCurrentSysuiState();

        // Update window state
        int newState = STATE_ID + 1;
        mNavBarHelper.setWindowState(DISPLAY_ID, WINDOW, newState);
        NavBarHelper.CurrentSysuiState state2 = mNavBarHelper.getCurrentSysuiState();

        // Ensure we get most recent state back
        assertThat(state1.mWindowState).isNotEqualTo(state2.mWindowState);
        assertThat(state1.mWindowStateDisplayId).isEqualTo(state2.mWindowStateDisplayId);
        assertThat(state2.mWindowState).isEqualTo(newState);
    }

    @Test
    public void ignoreNonNavbarSysuiState() {
        mNavBarHelper.setWindowState(DISPLAY_ID, WINDOW, STATE_ID);
        NavBarHelper.CurrentSysuiState state1 = mNavBarHelper.getCurrentSysuiState();

        // Update window state for other window type
        int newState = STATE_ID + 1;
        mNavBarHelper.setWindowState(DISPLAY_ID, WINDOW + 1, newState);
        NavBarHelper.CurrentSysuiState state2 = mNavBarHelper.getCurrentSysuiState();

        // Ensure we get first state back
        assertThat(state2.mWindowState).isEqualTo(state1.mWindowState);
        assertThat(state2.mWindowState).isNotEqualTo(newState);
    }

    @Test
    public void configUpdatePropagatesToEdgeBackGestureHandler() {
        mConfigurationController.onConfigurationChanged(Configuration.EMPTY);
        verify(mEdgeBackGestureHandler, times(1)).onConfigurationChanged(any());
    }

    private List<String> createFakeShortcutTargets() {
        return new ArrayList<>(List.of("a", "b", "c", "d"));
    }
}
