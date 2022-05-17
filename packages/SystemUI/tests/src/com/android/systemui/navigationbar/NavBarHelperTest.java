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
import android.view.accessibility.AccessibilityManager;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.accessibility.AccessibilityButtonModeObserver;
import com.android.systemui.accessibility.AccessibilityButtonTargetsObserver;
import com.android.systemui.accessibility.SystemActions;
import com.android.systemui.assist.AssistManager;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.CentralSurfaces;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dagger.Lazy;

/**
 * Tests for {@link NavBarHelper}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
public class NavBarHelperTest extends SysuiTestCase {

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
    private AccessibilityManager.AccessibilityServicesStateChangeListener
            mAccessibilityServicesStateChangeListener;

    private static final int ACCESSIBILITY_BUTTON_CLICKABLE_STATE =
            SYSUI_STATE_A11Y_BUTTON_CLICKABLE | SYSUI_STATE_A11Y_BUTTON_LONG_CLICKABLE;
    private NavBarHelper mNavBarHelper;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mAssistManagerLazy.get()).thenReturn(mAssistManager);
        when(mAssistManager.getAssistInfoForUser(anyInt())).thenReturn(mAssistantComponent);
        when(mUserTracker.getUserId()).thenReturn(1);

        doAnswer((invocation) -> mAccessibilityServicesStateChangeListener =
                invocation.getArgument(0)).when(
                mAccessibilityManager).addAccessibilityServicesStateChangeListener(any());
        mNavBarHelper = new NavBarHelper(mContext, mAccessibilityManager,
                mAccessibilityButtonModeObserver, mAccessibilityButtonTargetObserver,
                mSystemActions, mOverviewProxyService, mAssistManagerLazy,
                () -> Optional.of(mock(CentralSurfaces.class)),
                mNavigationModeController, mUserTracker, mDumpManager);

    }

    @Test
    public void registerListenersInCtor() {
        verify(mAccessibilityButtonModeObserver, times(1)).addListener(mNavBarHelper);
        verify(mNavigationModeController, times(1)).addListener(mNavBarHelper);
        verify(mOverviewProxyService, times(1)).addCallback(mNavBarHelper);
    }

    @Test
    public void registerAssistantContentObserver() {
        mNavBarHelper.init();
        verify(mAssistManager, times(1)).getAssistInfoForUser(anyInt());
    }

    @Test
    public void callbacksFiredWhenRegistering() {
        mNavBarHelper.init();
        mNavBarHelper.registerNavTaskStateUpdater(mNavbarTaskbarStateUpdater);
        verify(mNavbarTaskbarStateUpdater, times(1))
                .updateAccessibilityServicesState();
        verify(mNavbarTaskbarStateUpdater, times(1))
                .updateAssistantAvailable(anyBoolean());
    }

    @Test
    public void assistantCallbacksFiredAfterConnecting() {
        mNavBarHelper.init();
        // 1st set of callbacks get called when registering
        mNavBarHelper.registerNavTaskStateUpdater(mNavbarTaskbarStateUpdater);

        mNavBarHelper.onConnectionChanged(false);
        // assert no more callbacks fired
        verify(mNavbarTaskbarStateUpdater, times(1))
                .updateAccessibilityServicesState();
        verify(mNavbarTaskbarStateUpdater, times(1))
                .updateAssistantAvailable(anyBoolean());

        mNavBarHelper.onConnectionChanged(true);
        // assert no more callbacks fired
        verify(mNavbarTaskbarStateUpdater, times(1))
                .updateAccessibilityServicesState();
        verify(mNavbarTaskbarStateUpdater, times(2))
                .updateAssistantAvailable(anyBoolean());
    }

    @Test
    public void a11yCallbacksFiredAfterModeChange() {
        mNavBarHelper.init();
        // 1st set of callbacks get called when registering
        mNavBarHelper.registerNavTaskStateUpdater(mNavbarTaskbarStateUpdater);

        mNavBarHelper.onAccessibilityButtonModeChanged(0);
        verify(mNavbarTaskbarStateUpdater, times(2))
                .updateAccessibilityServicesState();
        verify(mNavbarTaskbarStateUpdater, times(1))
                .updateAssistantAvailable(anyBoolean());
    }

    @Test
    public void assistantCallbacksFiredAfterNavModeChange() {
        mNavBarHelper.init();
        // 1st set of callbacks get called when registering
        mNavBarHelper.registerNavTaskStateUpdater(mNavbarTaskbarStateUpdater);

        mNavBarHelper.onNavigationModeChanged(0);
        verify(mNavbarTaskbarStateUpdater, times(1))
                .updateAccessibilityServicesState();
        verify(mNavbarTaskbarStateUpdater, times(2))
                .updateAssistantAvailable(anyBoolean());
    }

    @Test
    public void removeListenerNoCallbacksFired() {
        mNavBarHelper.init();
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
                .updateAssistantAvailable(anyBoolean());
    }

    @Test
    public void initNavBarHelper_buttonModeNavBar_a11yButtonClickableState() {
        when(mAccessibilityManager.getAccessibilityShortcutTargets(
                AccessibilityManager.ACCESSIBILITY_BUTTON)).thenReturn(createFakeShortcutTargets());

        mNavBarHelper.init();

        assertThat(mNavBarHelper.getA11yButtonState()).isEqualTo(
                ACCESSIBILITY_BUTTON_CLICKABLE_STATE);
    }

    @Test
    public void initAccessibilityStateWithFloatingMenuModeAndTargets_disableClickableState() {
        when(mAccessibilityButtonModeObserver.getCurrentAccessibilityButtonMode()).thenReturn(
                ACCESSIBILITY_BUTTON_MODE_FLOATING_MENU);
        mNavBarHelper.init();

        assertThat(mNavBarHelper.getA11yButtonState()).isEqualTo(/* disable_clickable_state */ 0);
    }

    @Test
    public void onA11yServicesStateChangedWithMultipleServices_a11yButtonClickableState() {
        when(mAccessibilityButtonModeObserver.getCurrentAccessibilityButtonMode()).thenReturn(
                ACCESSIBILITY_BUTTON_MODE_NAVIGATION_BAR);

        when(mAccessibilityManager.getAccessibilityShortcutTargets(
                AccessibilityManager.ACCESSIBILITY_BUTTON)).thenReturn(createFakeShortcutTargets());
        mAccessibilityServicesStateChangeListener.onAccessibilityServicesStateChanged(
                mAccessibilityManager);

        assertThat(mNavBarHelper.getA11yButtonState()).isEqualTo(
                ACCESSIBILITY_BUTTON_CLICKABLE_STATE);
    }

    private List<String> createFakeShortcutTargets() {
        return new ArrayList<>(List.of("a", "b", "c", "d"));
    }
}
