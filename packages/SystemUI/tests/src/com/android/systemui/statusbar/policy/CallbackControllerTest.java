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

package com.android.systemui.statusbar.policy;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.Lifecycle.Event;
import androidx.lifecycle.LifecycleEventObserver;
import androidx.lifecycle.LifecycleOwner;

import com.android.systemui.SysuiTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

@RunWithLooper
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class CallbackControllerTest extends SysuiTestCase {

    @Test
    public void testAddCallback() {
        Lifecycle lifecycle = mock(Lifecycle.class);
        LifecycleOwner owner = () -> lifecycle;
        Object callback = new Object();
        Controller controller = mock(Controller.class);

        // observe and get the lifecycle observer that gets registered.
        ArgumentCaptor<LifecycleEventObserver> observer =
                ArgumentCaptor.forClass(LifecycleEventObserver.class);
        controller.observe(owner, callback);
        verify(lifecycle).addObserver(observer.capture());

        // move to resume state and make sure the callback gets registered.
        observer.getValue().onStateChanged(owner, Event.ON_RESUME);
        verify(controller).addCallback(eq(callback));
    }

    @Test
    public void testRemoveCallback() {
        Lifecycle lifecycle = mock(Lifecycle.class);
        LifecycleOwner owner = () -> lifecycle;
        Object callback = new Object();
        Controller controller = mock(Controller.class);

        // observe and get the lifecycle observer that gets registered.
        ArgumentCaptor<LifecycleEventObserver> observer =
                ArgumentCaptor.forClass(LifecycleEventObserver.class);
        controller.observe(owner, callback);
        verify(lifecycle).addObserver(observer.capture());

        // move to pause state and make sure the callback gets unregistered.
        observer.getValue().onStateChanged(owner, Event.ON_PAUSE);
        verify(controller).removeCallback(eq(callback));
    }

    private static class Controller implements CallbackController<Object> {
        @Override
        public void addCallback(Object listener) {
        }

        @Override
        public void removeCallback(Object listener) {
        }
    }
}
