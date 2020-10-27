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

package com.android.wm.shell.pip;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.graphics.Rect;
import android.os.RemoteException;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.Rational;
import android.view.DisplayInfo;
import android.window.WindowContainerToken;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.splitscreen.SplitScreen;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

/**
 * Unit tests for {@link PipTaskOrganizer}
 */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class PipTaskOrganizerTest extends PipTestCase {
    private PipTaskOrganizer mSpiedPipTaskOrganizer;

    @Mock private DisplayController mMockdDisplayController;
    @Mock private PipBoundsHandler mMockPipBoundsHandler;
    @Mock private PipSurfaceTransactionHelper mMockPipSurfaceTransactionHelper;
    @Mock private PipUiEventLogger mMockPipUiEventLogger;
    @Mock private Optional<SplitScreen> mMockOptionalSplitScreen;
    @Mock private ShellTaskOrganizer mMockShellTaskOrganizer;
    private PipBoundsState mPipBoundsState;

    private ComponentName mComponent1;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        mComponent1 = new ComponentName(mContext, "component1");
        mPipBoundsState = new PipBoundsState();
        mSpiedPipTaskOrganizer = spy(new PipTaskOrganizer(mContext, mPipBoundsState,
                mMockPipBoundsHandler, mMockPipSurfaceTransactionHelper, mMockOptionalSplitScreen,
                mMockdDisplayController, mMockPipUiEventLogger, mMockShellTaskOrganizer));
        preparePipTaskOrg();
    }

    @Test
    public void instantiatePipTaskOrganizer_addsTaskListener() {
        verify(mMockShellTaskOrganizer).addListenerForType(any(), anyInt());
    }

    @Test
    public void instantiatePipTaskOrganizer_addsDisplayWindowListener() {
        verify(mMockdDisplayController).addDisplayWindowListener(any());
    }

    @Test
    public void startSwipePipToHome_updatesAspectRatio() {
        final Rational aspectRatio = new Rational(2, 1);

        mSpiedPipTaskOrganizer.startSwipePipToHome(mComponent1, null, createPipParams(aspectRatio));

        assertEquals(aspectRatio.floatValue(), mPipBoundsState.getAspectRatio(), 0.01f);
    }

    @Test
    public void onTaskAppeared_updatesAspectRatio() {
        final Rational aspectRatio = new Rational(2, 1);

        mSpiedPipTaskOrganizer.onTaskAppeared(createTaskInfo(
                createPipParams(aspectRatio)), null /* leash */);

        assertEquals(aspectRatio.floatValue(), mPipBoundsState.getAspectRatio(), 0.01f);
    }

    @Test
    public void onTaskInfoChanged_updatesAspectRatioIfChanged() {
        final Rational startAspectRatio = new Rational(2, 1);
        final Rational newAspectRatio = new Rational(1, 2);
        mSpiedPipTaskOrganizer.onTaskAppeared(createTaskInfo(
                createPipParams(startAspectRatio)), null /* leash */);

        mSpiedPipTaskOrganizer.onTaskInfoChanged(createTaskInfo(createPipParams(newAspectRatio)));

        assertEquals(newAspectRatio.floatValue(), mPipBoundsState.getAspectRatio(), 0.01f);
    }

    private void preparePipTaskOrg() {
        final DisplayInfo info = new DisplayInfo();
        when(mMockPipBoundsHandler.getDestinationBounds(any(), any())).thenReturn(new Rect());
        when(mMockPipBoundsHandler.getDestinationBounds(any(), any(), anyBoolean()))
                .thenReturn(new Rect());
        mPipBoundsState.setDisplayInfo(info);
        mSpiedPipTaskOrganizer.setOneShotAnimationType(PipAnimationController.ANIM_TYPE_ALPHA);
        doNothing().when(mSpiedPipTaskOrganizer).enterPipWithAlphaAnimation(any(), anyLong());
        doNothing().when(mSpiedPipTaskOrganizer).scheduleAnimateResizePip(any(), anyInt(), any());
    }

    private static ActivityManager.RunningTaskInfo createTaskInfo(PictureInPictureParams params) {
        final ActivityManager.RunningTaskInfo info = new ActivityManager.RunningTaskInfo();
        info.token = mock(WindowContainerToken.class);
        info.pictureInPictureParams = params;
        return info;
    }

    private static PictureInPictureParams createPipParams(Rational aspectRatio) {
        return new PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build();
    }
}
