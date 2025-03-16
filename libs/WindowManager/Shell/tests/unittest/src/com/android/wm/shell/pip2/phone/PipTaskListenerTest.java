/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.pip2.phone;

import static com.android.wm.shell.pip2.phone.PipTaskListener.ANIMATING_ASPECT_RATIO_CHANGE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.mockito.kotlin.MatchersKt.eq;
import static org.mockito.kotlin.VerificationKt.clearInvocations;
import static org.mockito.kotlin.VerificationKt.times;
import static org.mockito.kotlin.VerificationKt.verify;
import static org.mockito.kotlin.VerificationKt.verifyZeroInteractions;

import android.app.ActivityManager;
import android.app.PendingIntent;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.util.Rational;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTaskOrganizer;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.pip.PipBoundsAlgorithm;
import com.android.wm.shell.common.pip.PipBoundsState;
import com.android.wm.shell.pip2.animation.PipResizeAnimator;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit test against {@link PipTaskListener}.
 */
@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner.class)
public class PipTaskListenerTest {

    @Mock private Context mMockContext;
    @Mock private ShellTaskOrganizer mMockShellTaskOrganizer;
    @Mock private PipTransitionState mMockPipTransitionState;
    @Mock private SurfaceControl mMockLeash;
    @Mock private PipScheduler mMockPipScheduler;
    @Mock private PipBoundsState mMockPipBoundsState;
    @Mock private PipBoundsAlgorithm mMockPipBoundsAlgorithm;
    @Mock private ShellExecutor mMockShellExecutor;

    @Mock private Icon mMockIcon;
    @Mock private PendingIntent mMockPendingIntent;

    @Mock private PipTaskListener.PipParamsChangedCallback mMockPipParamsChangedCallback;

    @Mock private PipResizeAnimator mMockPipResizeAnimator;

    private ArgumentCaptor<List<RemoteAction>> mRemoteActionListCaptor;

    private PipTaskListener mPipTaskListener;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mRemoteActionListCaptor = ArgumentCaptor.forClass(List.class);
        when(mMockPipTransitionState.getPinnedTaskLeash()).thenReturn(mMockLeash);
    }

    @Test
    public void constructor_addPipTransitionStateChangedListener() {
        mPipTaskListener = new PipTaskListener(mMockContext, mMockShellTaskOrganizer,
                mMockPipTransitionState, mMockPipScheduler, mMockPipBoundsState,
                mMockPipBoundsAlgorithm, mMockShellExecutor);

        verify(mMockPipTransitionState).addPipTransitionStateChangedListener(eq(mPipTaskListener));
    }

    @Test
    public void setPictureInPictureParams_updatePictureInPictureParams() {
        mPipTaskListener = new PipTaskListener(mMockContext, mMockShellTaskOrganizer,
                mMockPipTransitionState, mMockPipScheduler, mMockPipBoundsState,
                mMockPipBoundsAlgorithm, mMockShellExecutor);
        Rational aspectRatio = new Rational(4, 3);
        String action1 = "action1";

        mPipTaskListener.setPictureInPictureParams(getPictureInPictureParams(
                aspectRatio, action1));

        PictureInPictureParams params = mPipTaskListener.getPictureInPictureParams();
        assertEquals(aspectRatio, params.getAspectRatio());
        assertTrue(params.hasSetActions());
        assertEquals(1, params.getActions().size());
        assertEquals(action1, params.getActions().get(0).getTitle());
    }

    @Test
    public void setPictureInPictureParams_withActionsChanged_callbackActionsChanged() {
        mPipTaskListener = new PipTaskListener(mMockContext, mMockShellTaskOrganizer,
                mMockPipTransitionState, mMockPipScheduler, mMockPipBoundsState,
                mMockPipBoundsAlgorithm, mMockShellExecutor);
        mPipTaskListener.addParamsChangedListener(mMockPipParamsChangedCallback);
        Rational aspectRatio = new Rational(4, 3);
        String action1 = "action1";
        mPipTaskListener.setPictureInPictureParams(getPictureInPictureParams(
                aspectRatio, action1));

        clearInvocations(mMockPipParamsChangedCallback);
        action1 = "modified action1";
        mPipTaskListener.setPictureInPictureParams(getPictureInPictureParams(
                aspectRatio, action1));

        verify(mMockPipParamsChangedCallback).onActionsChanged(
                mRemoteActionListCaptor.capture(), any());
        assertEquals(1, mRemoteActionListCaptor.getValue().size());
        assertEquals(action1, mRemoteActionListCaptor.getValue().get(0).getTitle());
    }

    @Test
    public void setPictureInPictureParams_withoutActionsChanged_doesNotCallbackActionsChanged() {
        mPipTaskListener = new PipTaskListener(mMockContext, mMockShellTaskOrganizer,
                mMockPipTransitionState, mMockPipScheduler, mMockPipBoundsState,
                mMockPipBoundsAlgorithm, mMockShellExecutor);
        mPipTaskListener.addParamsChangedListener(mMockPipParamsChangedCallback);
        Rational aspectRatio = new Rational(4, 3);
        String action1 = "action1";
        mPipTaskListener.setPictureInPictureParams(getPictureInPictureParams(
                aspectRatio, action1));

        clearInvocations(mMockPipParamsChangedCallback);
        mPipTaskListener.setPictureInPictureParams(getPictureInPictureParams(
                aspectRatio, action1));

        verifyZeroInteractions(mMockPipParamsChangedCallback);
    }

    @Test
    public void onTaskInfoChanged_withActionsChanged_callbackActionsChanged() {
        mPipTaskListener = new PipTaskListener(mMockContext, mMockShellTaskOrganizer,
                mMockPipTransitionState, mMockPipScheduler, mMockPipBoundsState,
                mMockPipBoundsAlgorithm, mMockShellExecutor);
        mPipTaskListener.addParamsChangedListener(mMockPipParamsChangedCallback);
        Rational aspectRatio = new Rational(4, 3);
        when(mMockPipBoundsState.getAspectRatio()).thenReturn(aspectRatio.toFloat());
        String action1 = "action1";
        mPipTaskListener.onTaskInfoChanged(getTaskInfo(aspectRatio, action1));

        clearInvocations(mMockPipParamsChangedCallback);
        clearInvocations(mMockPipBoundsState);
        action1 = "modified action1";
        mPipTaskListener.onTaskInfoChanged(getTaskInfo(aspectRatio, action1));

        verify(mMockPipTransitionState, times(0))
                .setOnIdlePipTransitionStateRunnable(any(Runnable.class));
        verify(mMockPipParamsChangedCallback).onActionsChanged(
                mRemoteActionListCaptor.capture(), any());
        assertEquals(1, mRemoteActionListCaptor.getValue().size());
        assertEquals(action1, mRemoteActionListCaptor.getValue().get(0).getTitle());
    }

    @Test
    public void onTaskInfoChanged_withAspectRatioChanged_callbackAspectRatioChanged() {
        mPipTaskListener = new PipTaskListener(mMockContext, mMockShellTaskOrganizer,
                mMockPipTransitionState, mMockPipScheduler, mMockPipBoundsState,
                mMockPipBoundsAlgorithm, mMockShellExecutor);
        mPipTaskListener.addParamsChangedListener(mMockPipParamsChangedCallback);
        Rational aspectRatio = new Rational(4, 3);
        when(mMockPipBoundsState.getAspectRatio()).thenReturn(aspectRatio.toFloat());
        String action1 = "action1";
        mPipTaskListener.onTaskInfoChanged(getTaskInfo(aspectRatio, action1));

        clearInvocations(mMockPipParamsChangedCallback);
        clearInvocations(mMockPipBoundsState);
        aspectRatio = new Rational(16, 9);
        mPipTaskListener.onTaskInfoChanged(getTaskInfo(aspectRatio, action1));

        verify(mMockPipTransitionState).setOnIdlePipTransitionStateRunnable(any(Runnable.class));
        verifyZeroInteractions(mMockPipParamsChangedCallback);
    }

    @Test
    public void onTaskInfoChanged_withoutParamsChanged_doesNotCallbackAspectRatioChanged() {
        mPipTaskListener = new PipTaskListener(mMockContext, mMockShellTaskOrganizer,
                mMockPipTransitionState, mMockPipScheduler, mMockPipBoundsState,
                mMockPipBoundsAlgorithm, mMockShellExecutor);
        mPipTaskListener.addParamsChangedListener(mMockPipParamsChangedCallback);
        Rational aspectRatio = new Rational(4, 3);
        when(mMockPipBoundsState.getAspectRatio()).thenReturn(aspectRatio.toFloat());
        String action1 = "action1";
        mPipTaskListener.onTaskInfoChanged(getTaskInfo(aspectRatio, action1));

        clearInvocations(mMockPipParamsChangedCallback);
        mPipTaskListener.onTaskInfoChanged(getTaskInfo(aspectRatio, action1));

        verifyZeroInteractions(mMockPipParamsChangedCallback);
        verify(mMockPipTransitionState, times(0))
                .setOnIdlePipTransitionStateRunnable(any(Runnable.class));
    }

    @Test
    public void onPipTransitionStateChanged_scheduledBoundsChangeWithAspectRatioChange_schedule() {
        mPipTaskListener = new PipTaskListener(mMockContext, mMockShellTaskOrganizer,
                mMockPipTransitionState, mMockPipScheduler, mMockPipBoundsState,
                mMockPipBoundsAlgorithm, mMockShellExecutor);
        Bundle extras = new Bundle();
        extras.putBoolean(ANIMATING_ASPECT_RATIO_CHANGE, true);

        mPipTaskListener.onPipTransitionStateChanged(
                PipTransitionState.UNDEFINED, PipTransitionState.SCHEDULED_BOUNDS_CHANGE, extras);

        verify(mMockPipScheduler).scheduleAnimateResizePip(any(), anyBoolean(), anyInt());
    }

    @Test
    public void onPipTransitionStateChanged_scheduledBoundsChangeWithoutAspectRatioChange_noop() {
        mPipTaskListener = new PipTaskListener(mMockContext, mMockShellTaskOrganizer,
                mMockPipTransitionState, mMockPipScheduler, mMockPipBoundsState,
                mMockPipBoundsAlgorithm, mMockShellExecutor);
        Bundle extras = new Bundle();
        extras.putBoolean(ANIMATING_ASPECT_RATIO_CHANGE, false);

        mPipTaskListener.onPipTransitionStateChanged(
                PipTransitionState.UNDEFINED,
                PipTransitionState.SCHEDULED_BOUNDS_CHANGE,
                extras);

        verifyZeroInteractions(mMockPipScheduler);
    }

    @Test
    public void onPipTransitionStateChanged_changingPipBoundsWaitAspectRatioChange_animate() {
        mPipTaskListener = new PipTaskListener(mMockContext, mMockShellTaskOrganizer,
                mMockPipTransitionState, mMockPipScheduler, mMockPipBoundsState,
                mMockPipBoundsAlgorithm, mMockShellExecutor);
        Bundle extras = new Bundle();
        extras.putBoolean(ANIMATING_ASPECT_RATIO_CHANGE, true);
        extras.putParcelable(PipTransition.PIP_DESTINATION_BOUNDS,
                new Rect(0, 0, 100, 100));
        when(mMockPipBoundsState.getBounds()).thenReturn(new Rect(0, 0, 200, 200));

        mPipTaskListener.onPipTransitionStateChanged(
                PipTransitionState.UNDEFINED,
                PipTransitionState.SCHEDULED_BOUNDS_CHANGE,
                extras);
        mPipTaskListener.setPipResizeAnimatorSupplier(
                (context, leash, startTx, finishTx, baseBounds, startBounds, endBounds,
                        duration, delta) -> mMockPipResizeAnimator);
        mPipTaskListener.onPipTransitionStateChanged(
                PipTransitionState.SCHEDULED_BOUNDS_CHANGE,
                PipTransitionState.CHANGING_PIP_BOUNDS,
                extras);

        verify(mMockPipResizeAnimator, times(1)).start();
    }

    @Test
    public void onPipTransitionStateChanged_changingPipBoundsNotAspectRatioChange_noop() {
        mPipTaskListener = new PipTaskListener(mMockContext, mMockShellTaskOrganizer,
                mMockPipTransitionState, mMockPipScheduler, mMockPipBoundsState,
                mMockPipBoundsAlgorithm, mMockShellExecutor);
        Bundle extras = new Bundle();
        extras.putBoolean(ANIMATING_ASPECT_RATIO_CHANGE, false);
        extras.putParcelable(PipTransition.PIP_DESTINATION_BOUNDS,
                new Rect(0, 0, 100, 100));
        when(mMockPipBoundsState.getBounds()).thenReturn(new Rect(0, 0, 200, 200));

        mPipTaskListener.onPipTransitionStateChanged(
                PipTransitionState.UNDEFINED,
                PipTransitionState.SCHEDULED_BOUNDS_CHANGE,
                extras);
        mPipTaskListener.setPipResizeAnimatorSupplier(
                (context, leash, startTx, finishTx, baseBounds, startBounds, endBounds,
                        duration, delta) -> mMockPipResizeAnimator);
        mPipTaskListener.onPipTransitionStateChanged(
                PipTransitionState.SCHEDULED_BOUNDS_CHANGE,
                PipTransitionState.CHANGING_PIP_BOUNDS,
                extras);

        verify(mMockPipResizeAnimator, times(0)).start();
    }

    private PictureInPictureParams getPictureInPictureParams(Rational aspectRatio,
            String... actions) {
        final PictureInPictureParams.Builder builder = new PictureInPictureParams.Builder();
        builder.setAspectRatio(aspectRatio);
        final List<RemoteAction> remoteActions = new ArrayList<>();
        for (String action : actions) {
            remoteActions.add(new RemoteAction(mMockIcon, action, action, mMockPendingIntent));
        }
        if (!remoteActions.isEmpty()) {
            builder.setActions(remoteActions);
        }
        return builder.build();
    }

    private ActivityManager.RunningTaskInfo getTaskInfo(Rational aspectRatio,
            String... actions) {
        final ActivityManager.RunningTaskInfo taskInfo = new ActivityManager.RunningTaskInfo();
        taskInfo.pictureInPictureParams = getPictureInPictureParams(aspectRatio, actions);
        return taskInfo;
    }
}
