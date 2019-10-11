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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.lifecycle.LifecycleEventObserver;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiBaseFragmentTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWithLooper
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class LifecycleFragmentTest extends SysuiBaseFragmentTest {

    public LifecycleFragmentTest() {
        super(LifecycleFragment.class);
    }

    @Test
    public void testCreateLifecycle() {
        LifecycleFragment fragment = (LifecycleFragment) mFragment;
        LifecycleEventObserver observer = mock(LifecycleEventObserver.class);
        fragment.getLifecycle().addObserver(observer);

        mFragments.dispatchCreate();
        TestableLooper.get(this).processAllMessages();

        verify(observer).onStateChanged(eq(fragment), eq(ON_CREATE));
    }

    @Test
    public void testResumeLifecycle() {
        LifecycleFragment fragment = (LifecycleFragment) mFragment;
        LifecycleEventObserver observer = mock(LifecycleEventObserver.class);
        fragment.getLifecycle().addObserver(observer);

        mFragments.dispatchResume();
        TestableLooper.get(this).processAllMessages();

        verify(observer).onStateChanged(eq(fragment), eq(ON_RESUME));
    }

    @Test
    public void testStartLifecycle() {
        LifecycleFragment fragment = (LifecycleFragment) mFragment;
        LifecycleEventObserver observer = mock(LifecycleEventObserver.class);
        fragment.getLifecycle().addObserver(observer);

        mFragments.dispatchStart();
        TestableLooper.get(this).processAllMessages();

        verify(observer).onStateChanged(eq(fragment), eq(ON_START));
    }

    @Test
    public void testStopLifecycle() {
        LifecycleFragment fragment = (LifecycleFragment) mFragment;
        LifecycleEventObserver observer = mock(LifecycleEventObserver.class);
        fragment.getLifecycle().addObserver(observer);

        mFragments.dispatchStart();
        TestableLooper.get(this).processAllMessages();
        mFragments.dispatchStop();
        TestableLooper.get(this).processAllMessages();

        verify(observer).onStateChanged(eq(fragment), eq(ON_STOP));
    }

    @Test
    public void testPauseLifecycle() {
        LifecycleFragment fragment = (LifecycleFragment) mFragment;
        LifecycleEventObserver observer = mock(LifecycleEventObserver.class);
        fragment.getLifecycle().addObserver(observer);

        mFragments.dispatchResume();
        TestableLooper.get(this).processAllMessages();
        mFragments.dispatchPause();
        TestableLooper.get(this).processAllMessages();

        verify(observer).onStateChanged(eq(fragment), eq(ON_PAUSE));
    }

    @Test
    public void testDestroyLifecycle() {
        LifecycleFragment fragment = (LifecycleFragment) mFragment;
        LifecycleEventObserver observer = mock(LifecycleEventObserver.class);
        fragment.getLifecycle().addObserver(observer);

        mFragments.dispatchCreate();
        TestableLooper.get(this).processAllMessages();
        mFragments.dispatchDestroy();
        TestableLooper.get(this).processAllMessages();
        mFragments = null;

        verify(observer).onStateChanged(eq(fragment), eq(ON_DESTROY));
    }
}
