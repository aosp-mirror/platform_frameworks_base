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

package com.android.systemui.recents;

import static android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.pm.PackageManager;
import android.os.RemoteException;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableContext;
import android.testing.TestableLooper;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.model.SysUiState;
import com.android.systemui.navigationbar.NavigationBarController;
import com.android.systemui.navigationbar.NavigationModeController;
import com.android.systemui.shared.recents.IPinnedStackAnimationListener;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.statusbar.phone.StatusBar;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreen;
import com.android.wm.shell.pip.Pip;
import com.android.wm.shell.splitscreen.SplitScreen;
import com.android.wm.shell.startingsurface.StartingSurface;
import com.android.wm.shell.transition.RemoteTransitions;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import dagger.Lazy;

/**
 * Unit tests for {@link com.android.systemui.recents.OverviewProxyService}
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class OverviewProxyServiceTest extends SysuiTestCase {
    private OverviewProxyService mSpiedOverviewProxyService;
    private TestableContext mSpiedContext;

    @Mock private BroadcastDispatcher mMockBroadcastDispatcher;
    @Mock private CommandQueue mMockCommandQueue;
    @Mock private Lazy<NavigationBarController> mMockNavBarControllerLazy;
    @Mock private IPinnedStackAnimationListener mMockPinnedStackAnimationListener;
    @Mock private NavigationModeController mMockNavModeController;
    @Mock private NotificationShadeWindowController mMockStatusBarWinController;
    @Mock private Optional<Pip> mMockPipOptional;
    @Mock private Optional<LegacySplitScreen> mMockLegacySplitScreenOptional;
    @Mock private Optional<SplitScreen> mMockSplitScreenOptional;
    @Mock private Optional<Lazy<StatusBar>> mMockStatusBarOptionalLazy;
    @Mock private Optional<com.android.wm.shell.onehanded.OneHanded> mMockOneHandedOptional;
    @Mock private PackageManager mPackageManager;
    @Mock private SysUiState mMockSysUiState;
    @Mock private RemoteTransitions mMockTransitions;
    @Mock private Optional<StartingSurface> mStartingSurface;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);

        mSpiedContext = spy(mContext);

        when(mPackageManager.hasSystemFeature(FEATURE_PICTURE_IN_PICTURE)).thenReturn(false);
        when(mSpiedContext.getPackageManager()).thenReturn(mPackageManager);

        mSpiedOverviewProxyService = spy(new OverviewProxyService(mSpiedContext, mMockCommandQueue,
                mMockNavBarControllerLazy, mMockNavModeController, mMockStatusBarWinController,
                mMockSysUiState, mMockPipOptional, mMockLegacySplitScreenOptional,
                mMockSplitScreenOptional, mMockStatusBarOptionalLazy, mMockOneHandedOptional,
                mMockBroadcastDispatcher, mMockTransitions, mStartingSurface));
    }

    @Test
    public void testNonPipDevice_shouldNotNotifySwipeToHomeFinished() throws RemoteException {
        mSpiedOverviewProxyService.mSysUiProxy.notifySwipeToHomeFinished();

        verify(mMockPipOptional, never()).ifPresent(any());
    }

    @Test
    public void testNonPipDevice_shouldNotSetPinnedStackAnimationListener() throws RemoteException {
        mSpiedOverviewProxyService.mSysUiProxy.setPinnedStackAnimationListener(
                mMockPinnedStackAnimationListener);

        verify(mMockPipOptional, never()).ifPresent(any());
    }

    @Test
    public void testNonPipDevice_shouldNotSetShelfHeight() throws RemoteException {
        mSpiedOverviewProxyService.mSysUiProxy.setShelfHeight(true /* visible */,
                100 /* shelfHeight */);

        verify(mMockPipOptional, never()).ifPresent(any());
    }
}
