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

package com.android.wm.shell.pip.phone;

import static android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE;

import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.RemoteException;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.Size;

import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.WindowManagerShellWrapper;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.onehanded.OneHandedController;
import com.android.wm.shell.pip.PipBoundsAlgorithm;
import com.android.wm.shell.pip.PipBoundsState;
import com.android.wm.shell.pip.PipMediaController;
import com.android.wm.shell.pip.PipTaskOrganizer;
import com.android.wm.shell.pip.PipTransitionController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

/**
 * Unit tests for {@link PipController}
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class PipControllerTest extends ShellTestCase {
    private PipController mPipController;

    @Mock private DisplayController mMockDisplayController;
    @Mock private PhonePipMenuController mMockPhonePipMenuController;
    @Mock private PipAppOpsListener mMockPipAppOpsListener;
    @Mock private PipBoundsAlgorithm mMockPipBoundsAlgorithm;
    @Mock private PipMediaController mMockPipMediaController;
    @Mock private PipTaskOrganizer mMockPipTaskOrganizer;
    @Mock private PipTransitionController mMockPipTransitionController;
    @Mock private PipTouchHandler mMockPipTouchHandler;
    @Mock private WindowManagerShellWrapper mMockWindowManagerShellWrapper;
    @Mock private PipBoundsState mMockPipBoundsState;
    @Mock private TaskStackListenerImpl mMockTaskStackListener;
    @Mock private ShellExecutor mMockExecutor;
    @Mock private Optional<OneHandedController> mMockOneHandedController;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(mMockExecutor).execute(any());
        mPipController = new PipController(mContext, mMockDisplayController,
                mMockPipAppOpsListener, mMockPipBoundsAlgorithm, mMockPipBoundsState,
                mMockPipMediaController, mMockPhonePipMenuController, mMockPipTaskOrganizer,
                mMockPipTouchHandler, mMockPipTransitionController, mMockWindowManagerShellWrapper,
                mMockTaskStackListener, mMockOneHandedController, mMockExecutor);
    }

    @Test
    public void instantiatePipController_registersPipTransitionCallback() {
        verify(mMockPipTransitionController).registerPipTransitionCallback(any());
    }

    @Test
    public void instantiatePipController_addsDisplayChangingController() {
        verify(mMockDisplayController).addDisplayChangingController(any());
    }

    @Test
    public void instantiatePipController_addsDisplayWindowListener() {
        verify(mMockDisplayController).addDisplayWindowListener(any());
    }

    @Test
    public void createPip_notSupported_returnsNull() {
        Context spyContext = spy(mContext);
        PackageManager mockPackageManager = mock(PackageManager.class);
        when(mockPackageManager.hasSystemFeature(FEATURE_PICTURE_IN_PICTURE)).thenReturn(false);
        when(spyContext.getPackageManager()).thenReturn(mockPackageManager);

        assertNull(PipController.create(spyContext, mMockDisplayController,
                mMockPipAppOpsListener, mMockPipBoundsAlgorithm, mMockPipBoundsState,
                mMockPipMediaController, mMockPhonePipMenuController, mMockPipTaskOrganizer,
                mMockPipTouchHandler, mMockPipTransitionController, mMockWindowManagerShellWrapper,
                mMockTaskStackListener, mMockOneHandedController, mMockExecutor));
    }

    @Test
    public void onActivityHidden_isLastPipComponentName_clearLastPipComponent() {
        final ComponentName component1 = new ComponentName(mContext, "component1");
        when(mMockPipBoundsState.getLastPipComponentName()).thenReturn(component1);

        mPipController.mPinnedTaskListener.onActivityHidden(component1);

        verify(mMockPipBoundsState).setLastPipComponentName(null);
    }

    @Test
    public void onActivityHidden_isNotLastPipComponentName_lastPipComponentNotCleared() {
        final ComponentName component1 = new ComponentName(mContext, "component1");
        final ComponentName component2 = new ComponentName(mContext, "component2");
        when(mMockPipBoundsState.getLastPipComponentName()).thenReturn(component1);

        mPipController.mPinnedTaskListener.onActivityHidden(component2);

        verify(mMockPipBoundsState, never()).setLastPipComponentName(null);
    }

    @Test
    public void saveReentryState_noUserResize_doesNotSaveSize() {
        final Rect bounds = new Rect(0, 0, 10, 10);
        when(mMockPipBoundsAlgorithm.getSnapFraction(bounds)).thenReturn(1.0f);
        when(mMockPipBoundsState.hasUserResizedPip()).thenReturn(false);

        mPipController.saveReentryState(bounds);

        verify(mMockPipBoundsState).saveReentryState(null, 1.0f);
    }

    @Test
    public void saveReentryState_userHasResized_savesSize() {
        final Rect bounds = new Rect(0, 0, 10, 10);
        final Rect resizedBounds = new Rect(0, 0, 30, 30);
        when(mMockPipBoundsAlgorithm.getSnapFraction(bounds)).thenReturn(1.0f);
        when(mMockPipTouchHandler.getUserResizeBounds()).thenReturn(resizedBounds);
        when(mMockPipBoundsState.hasUserResizedPip()).thenReturn(true);

        mPipController.saveReentryState(bounds);

        verify(mMockPipBoundsState).saveReentryState(new Size(30, 30), 1.0f);
    }
}
