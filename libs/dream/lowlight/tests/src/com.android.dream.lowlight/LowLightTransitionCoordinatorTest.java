/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.dream.lowlight;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.animation.Animator;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class LowLightTransitionCoordinatorTest {
    @Mock
    private LowLightTransitionCoordinator.LowLightEnterListener mEnterListener;

    @Mock
    private LowLightTransitionCoordinator.LowLightExitListener mExitListener;

    @Mock
    private Animator mAnimator;

    @Captor
    private ArgumentCaptor<Animator.AnimatorListener> mAnimatorListenerCaptor;

    @Mock
    private Runnable mRunnable;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void onEnterCalledOnListeners() {
        LowLightTransitionCoordinator coordinator = new LowLightTransitionCoordinator();

        coordinator.setLowLightEnterListener(mEnterListener);

        coordinator.notifyBeforeLowLightTransition(true, mRunnable);

        verify(mEnterListener).onBeforeEnterLowLight();
        verify(mRunnable).run();
    }

    @Test
    public void onExitCalledOnListeners() {
        LowLightTransitionCoordinator coordinator = new LowLightTransitionCoordinator();

        coordinator.setLowLightExitListener(mExitListener);

        coordinator.notifyBeforeLowLightTransition(false, mRunnable);

        verify(mExitListener).onBeforeExitLowLight();
        verify(mRunnable).run();
    }

    @Test
    public void listenerNotCalledAfterRemoval() {
        LowLightTransitionCoordinator coordinator = new LowLightTransitionCoordinator();

        coordinator.setLowLightEnterListener(mEnterListener);
        coordinator.setLowLightEnterListener(null);

        coordinator.notifyBeforeLowLightTransition(true, mRunnable);

        verifyZeroInteractions(mEnterListener);
        verify(mRunnable).run();
    }

    @Test
    public void runnableCalledAfterAnimationEnds() {
        when(mEnterListener.onBeforeEnterLowLight()).thenReturn(mAnimator);

        LowLightTransitionCoordinator coordinator = new LowLightTransitionCoordinator();
        coordinator.setLowLightEnterListener(mEnterListener);

        coordinator.notifyBeforeLowLightTransition(true, mRunnable);

        // Animator listener is added and the runnable is not run yet.
        verify(mAnimator).addListener(mAnimatorListenerCaptor.capture());
        verifyZeroInteractions(mRunnable);

        // Runnable is run once the animation ends.
        mAnimatorListenerCaptor.getValue().onAnimationEnd(null);
        verify(mRunnable).run();
    }
}
