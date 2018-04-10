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

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.Display.DEFAULT_DISPLAY;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.os.Binder;
import android.os.IInterface;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.IRecentsAnimationRunner;
import android.view.SurfaceControl;
import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * atest FrameworksServicesTests:com.android.server.wm.RecentsAnimationControllerTest
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class RecentsAnimationControllerTest extends WindowTestsBase {

    @Mock SurfaceControl mMockLeash;
    @Mock SurfaceControl.Transaction mMockTransaction;
    @Mock OnAnimationFinishedCallback mFinishedCallback;
    @Mock IRecentsAnimationRunner mMockRunner;
    @Mock RecentsAnimationController.RecentsAnimationCallbacks mAnimationCallbacks;
    private RecentsAnimationController mController;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        MockitoAnnotations.initMocks(this);
        when(mMockRunner.asBinder()).thenReturn(new Binder());
        mController = new RecentsAnimationController(sWm, mMockRunner, mAnimationCallbacks,
                DEFAULT_DISPLAY);
    }

    @Test
    public void testRemovedBeforeStarted_expectCanceled() throws Exception {
        final AppWindowToken appWindow = createAppWindowToken(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        AnimationAdapter adapter = mController.addAnimation(appWindow.getTask(),
                false /* isRecentTaskInvisible */);
        adapter.startAnimation(mMockLeash, mMockTransaction, mFinishedCallback);

        // Remove the app window so that the animation target can not be created
        appWindow.removeImmediately();
        mController.startAnimation();

        // Verify that the finish callback to reparent the leash is called
        verify(mFinishedCallback).onAnimationFinished(eq(adapter));
        // Verify the animation canceled callback to the app was made
        verify(mMockRunner).onAnimationCanceled();
        verifyNoMoreInteractionsExceptAsBinder(mMockRunner);
    }

    private static void verifyNoMoreInteractionsExceptAsBinder(IInterface binder) {
        verify(binder, atLeast(0)).asBinder();
        verifyNoMoreInteractions(binder);
    }
}
