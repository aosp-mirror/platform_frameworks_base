/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.keyguard;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.UiOffloadThread;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * runtest systemui -c com.android.systemui.keyguard.DismissCallbackRegistryTest
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class DismissCallbackRegistryTest extends SysuiTestCase {

    private final DismissCallbackRegistry mDismissCallbackRegistry = new DismissCallbackRegistry();
    private @Mock IKeyguardDismissCallback mMockCallback;
    private @Mock IKeyguardDismissCallback mMockCallback2;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testCancelled() throws Exception {
        mDismissCallbackRegistry.addCallback(mMockCallback);
        mDismissCallbackRegistry.notifyDismissCancelled();
        waitForUiOffloadThread();
        verify(mMockCallback).onDismissCancelled();
    }

    @Test
    public void testCancelled_multiple() throws Exception {
        mDismissCallbackRegistry.addCallback(mMockCallback);
        mDismissCallbackRegistry.addCallback(mMockCallback2);
        mDismissCallbackRegistry.notifyDismissCancelled();
        waitForUiOffloadThread();
        verify(mMockCallback).onDismissCancelled();
        verify(mMockCallback2).onDismissCancelled();
    }

    @Test
    public void testSucceeded() throws Exception {
        mDismissCallbackRegistry.addCallback(mMockCallback);
        mDismissCallbackRegistry.notifyDismissSucceeded();
        waitForUiOffloadThread();
        verify(mMockCallback).onDismissSucceeded();
    }

    @Test
    public void testSucceeded_multiple() throws Exception {
        mDismissCallbackRegistry.addCallback(mMockCallback);
        mDismissCallbackRegistry.addCallback(mMockCallback2);
        mDismissCallbackRegistry.notifyDismissSucceeded();
        waitForUiOffloadThread();
        verify(mMockCallback).onDismissSucceeded();
        verify(mMockCallback2).onDismissSucceeded();
    }

    @Test
    public void testOnlyOnce() throws Exception {
        mDismissCallbackRegistry.addCallback(mMockCallback);
        mDismissCallbackRegistry.notifyDismissSucceeded();
        mDismissCallbackRegistry.notifyDismissSucceeded();
        waitForUiOffloadThread();
        verify(mMockCallback, times(1)).onDismissSucceeded();
    }
}
