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

package com.android.settingslib.utils;

import static org.mockito.Mockito.verify;

import android.os.Handler;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class HandlerInjectorTest {

    public static final long TEST_DELAY_MILLIS = 0L;

    @Rule
    public final MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock
    public Handler mHandler;
    @Mock
    public Runnable mRunnable;

    private HandlerInjector mHandlerInjector;

    @Before
    public void setUp() {
        mHandlerInjector = new HandlerInjector(mHandler);
    }

    @Test
    public void postDelayed_doByMainThreadHandler() {
        mHandlerInjector.postDelayed(mRunnable, TEST_DELAY_MILLIS);

        verify(mHandler).postDelayed(mRunnable, TEST_DELAY_MILLIS);
    }

    @Test
    public void removeCallbacks_doByMainThreadHandler() {
        mHandlerInjector.removeCallbacks(mRunnable);

        verify(mHandler).removeCallbacks(mRunnable);
    }
}
