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

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.testing.ViewUtils;
import android.view.View;

import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWithLooper
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class SysuiLifecycleTest extends SysuiTestCase {

    @Test
    public void testAttach() {
        View v = new View(mContext);
        LifecycleEventObserver observer = mock(LifecycleEventObserver.class);
        LifecycleOwner lifecycle = viewAttachLifecycle(v);
        lifecycle.getLifecycle().addObserver(observer);

        ViewUtils.attachView(v);
        TestableLooper.get(this).processAllMessages();

        verify(observer).onStateChanged(eq(lifecycle), eq(ON_CREATE));
        verify(observer).onStateChanged(eq(lifecycle), eq(ON_START));
        verify(observer).onStateChanged(eq(lifecycle), eq(ON_RESUME));

        ViewUtils.detachView(v);
        TestableLooper.get(this).processAllMessages();
    }

    @Test
    public void testDetach() {
        View v = new View(mContext);
        LifecycleEventObserver observer = mock(LifecycleEventObserver.class);
        LifecycleOwner lifecycle = viewAttachLifecycle(v);
        lifecycle.getLifecycle().addObserver(observer);

        ViewUtils.attachView(v);
        TestableLooper.get(this).processAllMessages();

        ViewUtils.detachView(v);
        TestableLooper.get(this).processAllMessages();

        verify(observer).onStateChanged(eq(lifecycle), eq(ON_PAUSE));
        verify(observer).onStateChanged(eq(lifecycle), eq(ON_STOP));
        verify(observer).onStateChanged(eq(lifecycle), eq(ON_DESTROY));
    }
}
