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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.app.PictureInPictureParams;
import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.os.RemoteException;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.Rational;
import android.util.Size;
import android.view.DisplayInfo;
import android.window.WindowContainerToken;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.ShellTestCase;
import com.android.wm.shell.TestShellExecutor;
import com.android.wm.shell.common.DisplayController;
import com.android.wm.shell.common.DisplayLayout;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.legacysplitscreen.LegacySplitScreenController;
import com.android.wm.shell.pip.phone.PhonePipMenuController;

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
public class PipTaskOrganizerTest extends ShellTestCase {
    private PipTaskOrganizer mSpiedPipTaskOrganizer;

    @Mock private DisplayController mMockDisplayController;
    @Mock private SyncTransactionQueue mMockSyncTransactionQueue;
    @Mock private PhonePipMenuController mMockPhonePipMenuController;
    @Mock private PipAnimationController mMockPipAnimationController;
    @Mock private PipTransitionController mMockPipTransitionController;
    @Mock private PipSurfaceTransactionHelper mMockPipSurfaceTransactionHelper;
    @Mock private PipUiEventLogger mMockPipUiEventLogger;
    @Mock private Optional<LegacySplitScreenController> mMockOptionalSplitScreen;
    @Mock private ShellTaskOrganizer mMockShellTaskOrganizer;
    private TestShellExecutor mMainExecutor;
    private PipBoundsState mPipBoundsState;
    private PipBoundsAlgorithm mPipBoundsAlgorithm;

    private ComponentName mComponent1;
    private ComponentName mComponent2;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        mComponent1 = new ComponentName(mContext, "component1");
        mComponent2 = new ComponentName(mContext, "component2");
        mPipBoundsState = new PipBoundsState(mContext);
        mPipBoundsAlgorithm = new PipBoundsAlgorithm(mContext, mPipBoundsState);
        mMainExecutor = new TestShellExecutor();
        mSpiedPipTaskOrganizer = spy(new PipTaskOrganizer(mContext,
                mMockSyncTransactionQueue, mPipBoundsState,
                mPipBoundsAlgorithm, mMockPhonePipMenuController,
                mMockPipAnimationController, mMockPipSurfaceTransactionHelper,
                mMockPipTransitionController, mMockOptionalSplitScreen, mMockDisplayController,
                mMockPipUiEventLogger, mMockShellTaskOrganizer, mMainExecutor));
        mMainExecutor.flushAll();
        preparePipTaskOrg();
    }

    @Test
    public void instantiatePipTaskOrganizer_addsTaskListener() {
        verify(mMockShellTaskOrganizer).addListenerForType(any(), anyInt());
    }

    @Test
    public void instantiatePipTaskOrganizer_addsDisplayWindowListener() {
        verify(mMockDisplayController).addDisplayWindowListener(any());
    }

    @Test
    public void startSwipePipToHome_updatesAspectRatio() {
        final Rational aspectRatio = new Rational(2, 1);

        mSpiedPipTaskOrganizer.startSwipePipToHome(mComponent1, null, createPipParams(aspectRatio));

        assertEquals(aspectRatio.floatValue(), mPipBoundsState.getAspectRatio(), 0.01f);
    }

    @Test
    public void startSwipePipToHome_updatesLastPipComponentName() {
        mSpiedPipTaskOrganizer.startSwipePipToHome(mComponent1, null, createPipParams(null));

        assertEquals(mComponent1, mPipBoundsState.getLastPipComponentName());
    }

    @Test
    public void startSwipePipToHome_updatesOverrideMinSize() {
        final Size minSize = new Size(400, 320);

        mSpiedPipTaskOrganizer.startSwipePipToHome(mComponent1, createActivityInfo(minSize),
                createPipParams(null));

        assertEquals(minSize, mPipBoundsState.getOverrideMinSize());
    }

    @Test
    public void onTaskAppeared_updatesAspectRatio() {
        final Rational aspectRatio = new Rational(2, 1);

        mSpiedPipTaskOrganizer.onTaskAppeared(createTaskInfo(mComponent1,
                createPipParams(aspectRatio)), null /* leash */);

        assertEquals(aspectRatio.floatValue(), mPipBoundsState.getAspectRatio(), 0.01f);
    }

    @Test
    public void onTaskAppeared_updatesLastPipComponentName() {
        mSpiedPipTaskOrganizer.onTaskAppeared(createTaskInfo(mComponent1, createPipParams(null)),
                null /* leash */);

        assertEquals(mComponent1, mPipBoundsState.getLastPipComponentName());
    }

    @Test
    public void onTaskAppeared_updatesOverrideMinSize() {
        final Size minSize = new Size(400, 320);

        mSpiedPipTaskOrganizer.onTaskAppeared(
                createTaskInfo(mComponent1, createPipParams(null), minSize),
                null /* leash */);

        assertEquals(minSize, mPipBoundsState.getOverrideMinSize());
    }

    @Test
    public void onTaskInfoChanged_updatesAspectRatioIfChanged() {
        final Rational startAspectRatio = new Rational(2, 1);
        final Rational newAspectRatio = new Rational(1, 2);
        mSpiedPipTaskOrganizer.onTaskAppeared(createTaskInfo(mComponent1,
                createPipParams(startAspectRatio)), null /* leash */);

        mSpiedPipTaskOrganizer.onTaskInfoChanged(createTaskInfo(mComponent1,
                createPipParams(newAspectRatio)));

        assertEquals(newAspectRatio.floatValue(), mPipBoundsState.getAspectRatio(), 0.01f);
    }

    @Test
    public void onTaskInfoChanged_updatesLastPipComponentName() {
        mSpiedPipTaskOrganizer.onTaskAppeared(createTaskInfo(mComponent1,
                createPipParams(null)), null /* leash */);

        mSpiedPipTaskOrganizer.onTaskInfoChanged(createTaskInfo(mComponent2,
                createPipParams(null)));

        assertEquals(mComponent2, mPipBoundsState.getLastPipComponentName());
    }

    @Test
    public void onTaskInfoChanged_updatesOverrideMinSize() {
        mSpiedPipTaskOrganizer.onTaskAppeared(createTaskInfo(mComponent1,
                createPipParams(null)), null /* leash */);

        final Size minSize = new Size(400, 320);
        mSpiedPipTaskOrganizer.onTaskInfoChanged(createTaskInfo(mComponent2,
                createPipParams(null), minSize));

        assertEquals(minSize, mPipBoundsState.getOverrideMinSize());
    }

    private void preparePipTaskOrg() {
        final DisplayInfo info = new DisplayInfo();
        mPipBoundsState.setDisplayLayout(new DisplayLayout(info,
                mContext.getResources(), true, true));
        mSpiedPipTaskOrganizer.setOneShotAnimationType(PipAnimationController.ANIM_TYPE_ALPHA);
        doNothing().when(mSpiedPipTaskOrganizer).enterPipWithAlphaAnimation(any(), anyLong());
        doNothing().when(mSpiedPipTaskOrganizer).scheduleAnimateResizePip(any(), anyInt(), any());
    }

    private static ActivityManager.RunningTaskInfo createTaskInfo(
            ComponentName componentName, PictureInPictureParams params) {
        return createTaskInfo(componentName, params, null /* minSize */);
    }

    private static ActivityManager.RunningTaskInfo createTaskInfo(
            ComponentName componentName, PictureInPictureParams params, Size minSize) {
        final ActivityManager.RunningTaskInfo info = new ActivityManager.RunningTaskInfo();
        info.token = mock(WindowContainerToken.class);
        info.pictureInPictureParams = params;
        info.topActivity = componentName;
        if (minSize != null) {
            info.topActivityInfo = createActivityInfo(minSize);
        }
        return info;
    }

    private static ActivityInfo createActivityInfo(Size minSize) {
        final ActivityInfo activityInfo = new ActivityInfo();
        activityInfo.windowLayout = new ActivityInfo.WindowLayout(
                0, 0, 0, 0, 0, minSize.getWidth(), minSize.getHeight());
        return activityInfo;
    }

    private static PictureInPictureParams createPipParams(Rational aspectRatio) {
        return new PictureInPictureParams.Builder()
                .setAspectRatio(aspectRatio)
                .build();
    }
}
