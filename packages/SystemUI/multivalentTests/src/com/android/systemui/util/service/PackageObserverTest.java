/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.util.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class PackageObserverTest extends SysuiTestCase {
    @Mock
    Context mContext;

    @Mock
    Observer.Callback mCallback;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testChange() {
        final PackageObserver observer = new PackageObserver(mContext,
                ComponentName.unflattenFromString("com.foo.bar/baz"));
        final ArgumentCaptor<BroadcastReceiver> receiverCapture =
                ArgumentCaptor.forClass(BroadcastReceiver.class);

        observer.addCallback(mCallback);

        // Verify broadcast receiver registered.
        verify(mContext).registerReceiver(receiverCapture.capture(), any(), anyInt());

        // Simulate package change.
        receiverCapture.getValue().onReceive(mContext, new Intent());

        // Check that callback was informed.
        verify(mCallback).onSourceChanged();

        observer.removeCallback(mCallback);

        // Make sure receiver is unregistered on last callback removal
        verify(mContext).unregisterReceiver(receiverCapture.getValue());
    }
}
