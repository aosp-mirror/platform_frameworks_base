/*
 * Copyright (C) 2018 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.statusbar.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.os.RemoteException;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;
import android.view.View;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationShadeDepthController;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.NotificationPanelViewController;
import com.android.systemui.statusbar.phone.NotificationShadeWindowView;
import com.android.systemui.statusbar.phone.NotificationShadeWindowViewController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ActivityLaunchAnimatorTest extends SysuiTestCase {

    private ActivityLaunchAnimator mLaunchAnimator;
    @Mock
    private ActivityLaunchAnimator.Callback mCallback;
    @Mock
    private NotificationShadeWindowViewController mNotificationShadeWindowViewController;
    @Mock
    private NotificationShadeWindowView mNotificationShadeWindowView;
    @Mock
    private NotificationListContainer mNotificationContainer;
    @Mock
    private ExpandableNotificationRow mRow;
    @Mock
    private NotificationShadeDepthController mNotificationShadeDepthController;
    @Mock
    private NotificationPanelViewController mNotificationPanelViewController;
    @Rule
    public MockitoRule rule = MockitoJUnit.rule();
    private FakeExecutor mExecutor;

    @Before
    public void setUp() throws Exception {
        mExecutor = new FakeExecutor(new FakeSystemClock());
        when(mNotificationShadeWindowViewController.getView())
                .thenReturn(mNotificationShadeWindowView);
        when(mNotificationShadeWindowView.getResources()).thenReturn(mContext.getResources());
        when(mCallback.areLaunchAnimationsEnabled()).thenReturn(true);
        mLaunchAnimator = new ActivityLaunchAnimator(
                mNotificationShadeWindowViewController,
                mCallback,
                mNotificationPanelViewController,
                mNotificationShadeDepthController,
                mNotificationContainer,
                mExecutor);
    }

    @Test
    public void testReturnsNullIfNotEnabled() {
        when(mCallback.areLaunchAnimationsEnabled()).thenReturn(false);
        RemoteAnimationAdapter launchAnimation = mLaunchAnimator.getLaunchAnimation(mRow,
                false /* occluded */);
        Assert.assertTrue("The LaunchAnimator generated an animation even though animations are "
                        + "disabled", launchAnimation == null);
    }

    @Test
    public void testNotWorkingWhenOccluded() {
        when(mCallback.areLaunchAnimationsEnabled()).thenReturn(false);
        RemoteAnimationAdapter launchAnimation = mLaunchAnimator.getLaunchAnimation(mRow,
                true /* occluded */);
        Assert.assertTrue("The LaunchAnimator generated an animation even though we're occluded",
                launchAnimation == null);
    }

    @Test
    public void testTimeoutCalled() {
        RemoteAnimationAdapter launchAnimation = mLaunchAnimator.getLaunchAnimation(mRow,
                false /* occluded */);
        Assert.assertTrue("No animation generated", launchAnimation != null);
        executePostsImmediately(mNotificationShadeWindowView);
        mLaunchAnimator.setLaunchResult(ActivityManager.START_SUCCESS,
                true /* wasIntentActivity */);
        verify(mCallback).onExpandAnimationTimedOut();
    }

    @Test
    public void testRowLinkBrokenOnAnimationStartFail() throws RemoteException {
        ActivityLaunchAnimator.AnimationRunner runner = mLaunchAnimator.new AnimationRunner(mRow,
                mExecutor);
        // WHEN onAnimationStart with no valid remote target
        runner.onAnimationStart(new RemoteAnimationTarget[0], new RemoteAnimationTarget[0],
                mock(IRemoteAnimationFinishedCallback.class));
        mExecutor.runAllReady();
        // THEN the row is nulled out so that it won't be retained
        Assert.assertTrue("The row should be null", runner.getRow() == null);
    }

    @Test
    public void testRowLinkBrokenOnAnimationCancelled() throws RemoteException {
        ActivityLaunchAnimator.AnimationRunner runner = mLaunchAnimator.new AnimationRunner(mRow,
                mExecutor);
        // WHEN onAnimationCancelled
        runner.onAnimationCancelled();
        mExecutor.runAllReady();
        // THEN the row is nulled out so that it won't be retained
        Assert.assertTrue("The row should be null", runner.getRow() == null);
    }

    private void executePostsImmediately(View view) {
        doAnswer((i) -> {
            Runnable run = i.getArgument(0);
            run.run();
            return null;
        }).when(view).post(any());
        doAnswer((i) -> {
            Runnable run = i.getArgument(0);
            run.run();
            return null;
        }).when(view).postDelayed(any(), anyLong());
    }
}

