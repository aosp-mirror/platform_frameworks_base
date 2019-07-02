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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyObject;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.RemoteAnimationAdapter;
import android.view.View;
import android.widget.FrameLayout;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationTestHelper;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.stack.NotificationListContainer;
import com.android.systemui.statusbar.phone.NotificationPanelView;
import com.android.systemui.statusbar.phone.StatusBarWindowView;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.stubbing.Answer;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class ActivityLaunchAnimatorTest extends SysuiTestCase {

    private ActivityLaunchAnimator mLaunchAnimator;
    private ActivityLaunchAnimator.Callback mCallback = mock(ActivityLaunchAnimator.Callback.class);
    private StatusBarWindowView mStatusBarWindowView = mock(StatusBarWindowView.class);
    private NotificationListContainer mNotificationContainer
            = mock(NotificationListContainer.class);
    private ExpandableNotificationRow mRow = mock(ExpandableNotificationRow.class);

    @Before
    public void setUp() throws Exception {
        when(mStatusBarWindowView.getResources()).thenReturn(mContext.getResources());
        when(mCallback.areLaunchAnimationsEnabled()).thenReturn(true);
        mLaunchAnimator = new ActivityLaunchAnimator(
                mStatusBarWindowView,
                mCallback,
                mock(NotificationPanelView.class),
                mNotificationContainer);

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
        executePostsImmediately(mStatusBarWindowView);
        mLaunchAnimator.setLaunchResult(ActivityManager.START_SUCCESS,
                true /* wasIntentActivity */);
        verify(mCallback).onExpandAnimationTimedOut();
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

