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
 * limitations under the License.
 */

package com.android.systemui.util;

import static androidx.lifecycle.Lifecycle.Event.ON_CREATE;
import static androidx.lifecycle.Lifecycle.Event.ON_DESTROY;
import static androidx.lifecycle.Lifecycle.Event.ON_PAUSE;
import static androidx.lifecycle.Lifecycle.Event.ON_RESUME;
import static androidx.lifecycle.Lifecycle.Event.ON_START;
import static androidx.lifecycle.Lifecycle.Event.ON_STOP;

import static com.android.systemui.util.SysuiLifecycle.viewAttachLifecycle;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.testing.ViewUtils;
import android.view.View;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class SysuiLifecycleTest extends SysuiTestCase {

    private View mView;

    @Before
    public void setUp() {
        mView = new View(mContext);
    }

    @After
    public void tearDown() {
        if (mView.isAttachedToWindow()) {
            ViewUtils.detachView(mView);
            TestableLooper.get(this).processAllMessages();
        }
    }

    @Test
    public void testAttach() {
        LifecycleEventObserver observer = mock(LifecycleEventObserver.class);
        LifecycleOwner lifecycle = viewAttachLifecycle(mView);
        lifecycle.getLifecycle().addObserver(observer);

        ViewUtils.attachView(mView);
        TestableLooper.get(this).processAllMessages();

        verify(observer).onStateChanged(eq(lifecycle), eq(ON_CREATE));
        verify(observer).onStateChanged(eq(lifecycle), eq(ON_START));
        verify(observer).onStateChanged(eq(lifecycle), eq(ON_RESUME));
    }

    @Test
    public void testDetach() {
        LifecycleEventObserver observer = mock(LifecycleEventObserver.class);
        LifecycleOwner lifecycle = viewAttachLifecycle(mView);
        lifecycle.getLifecycle().addObserver(observer);

        ViewUtils.attachView(mView);
        TestableLooper.get(this).processAllMessages();

        ViewUtils.detachView(mView);
        TestableLooper.get(this).processAllMessages();

        verify(observer).onStateChanged(eq(lifecycle), eq(ON_PAUSE));
        verify(observer).onStateChanged(eq(lifecycle), eq(ON_STOP));
        verify(observer).onStateChanged(eq(lifecycle), eq(ON_DESTROY));
    }

    @Test
    public void testStateBeforeAttach() {
        // WHEN a lifecycle is obtained from a view
        LifecycleOwner lifecycle = viewAttachLifecycle(mView);
        // THEN the lifecycle state should be INITIAZED
        assertThat(lifecycle.getLifecycle().getCurrentState()).isEqualTo(
                Lifecycle.State.INITIALIZED);
    }

    @Test
    public void testStateAfterAttach() {
        // WHEN a lifecycle is obtained from a view
        LifecycleOwner lifecycle = viewAttachLifecycle(mView);
        // AND the view is attached
        ViewUtils.attachView(mView);
        TestableLooper.get(this).processAllMessages();
        // THEN the lifecycle state should be RESUMED
        assertThat(lifecycle.getLifecycle().getCurrentState()).isEqualTo(Lifecycle.State.RESUMED);
    }

    @Test
    public void testStateAfterDetach() {
        // WHEN a lifecycle is obtained from a view
        LifecycleOwner lifecycle = viewAttachLifecycle(mView);
        // AND the view is detached
        ViewUtils.attachView(mView);
        TestableLooper.get(this).processAllMessages();
        ViewUtils.detachView(mView);
        TestableLooper.get(this).processAllMessages();
        // THEN the lifecycle state should be DESTROYED
        assertThat(lifecycle.getLifecycle().getCurrentState()).isEqualTo(Lifecycle.State.DESTROYED);
    }

    @Test
    public void testStateAfterReattach() {
        // WHEN a lifecycle is obtained from a view
        LifecycleOwner lifecycle = viewAttachLifecycle(mView);
        // AND the view is re-attached
        ViewUtils.attachView(mView);
        TestableLooper.get(this).processAllMessages();
        ViewUtils.detachView(mView);
        TestableLooper.get(this).processAllMessages();
        ViewUtils.attachView(mView);
        TestableLooper.get(this).processAllMessages();
        // THEN the lifecycle state should still be DESTROYED, err RESUMED?
        assertThat(lifecycle.getLifecycle().getCurrentState()).isEqualTo(Lifecycle.State.RESUMED);
    }

    @Test
    public void testStateWhenViewAlreadyAttached() {
        // GIVEN that a view is already attached
        ViewUtils.attachView(mView);
        TestableLooper.get(this).processAllMessages();
        // WHEN a lifecycle is obtained from a view
        LifecycleOwner lifecycle = viewAttachLifecycle(mView);
        // THEN the lifecycle state should be RESUMED
        assertThat(lifecycle.getLifecycle().getCurrentState()).isEqualTo(Lifecycle.State.RESUMED);
    }

    @Test
    public void testStateWhenViewAlreadyDetached() {
        // GIVEN that a view is already detached
        ViewUtils.attachView(mView);
        TestableLooper.get(this).processAllMessages();
        ViewUtils.detachView(mView);
        TestableLooper.get(this).processAllMessages();
        // WHEN a lifecycle is obtained from a view
        LifecycleOwner lifecycle = viewAttachLifecycle(mView);
        // THEN the lifecycle state should be INITIALIZED
        assertThat(lifecycle.getLifecycle().getCurrentState()).isEqualTo(
                Lifecycle.State.INITIALIZED);
    }
}
