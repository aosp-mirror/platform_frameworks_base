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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
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
import com.android.systemui.assist.AssistManager;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.systemui.statusbar.policy.AccessibilityManagerWrapper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import dagger.Lazy;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class NavBarHelperTest extends SysuiTestCase {

    @Mock
    AccessibilityManager mAccessibilityManager;
    @Mock
    AccessibilityManagerWrapper mAccessibilityManagerWrapper;
    @Mock
    AccessibilityButtonModeObserver mAccessibilityButtonModeObserver;
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

    private NavBarHelper mNavBarHelper;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mAssistManagerLazy.get()).thenReturn(mAssistManager);
        when(mAssistManager.getAssistInfoForUser(anyInt())).thenReturn(mAssistantComponent);
        when(mUserTracker.getUserId()).thenReturn(1);

        mNavBarHelper = new NavBarHelper(mContext, mAccessibilityManager,
                mAccessibilityManagerWrapper, mAccessibilityButtonModeObserver,
                mOverviewProxyService, mAssistManagerLazy, () -> Optional.of(mock(StatusBar.class)),
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
}
