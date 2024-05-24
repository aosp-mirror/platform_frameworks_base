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

package com.android.systemui.dreams.touch.scrim;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.os.PowerManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.shade.ShadeExpansionChangeEvent;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
@android.platform.test.annotations.EnabledOnRavenwood
public class BouncerlessScrimControllerTest extends SysuiTestCase {
    @Mock
    BouncerlessScrimController.Callback mCallback;

    @Mock
    PowerManager mPowerManager;

    private final FakeExecutor mExecutor = new FakeExecutor(new FakeSystemClock());

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testWakeupOnSwipeOpen() {
        final BouncerlessScrimController scrimController =
                new BouncerlessScrimController(mExecutor, mPowerManager);
        scrimController.addCallback(mCallback);
        scrimController.expand(new ShadeExpansionChangeEvent(.5f, true, false, 0.0f));
        mExecutor.runAllReady();
        verify(mPowerManager).wakeUp(anyLong(), eq(PowerManager.WAKE_REASON_GESTURE), any());
        verify(mCallback).onWakeup();
    }

    @Test
    public void testExpansionPropagation() {
        final BouncerlessScrimController scrimController =
                new BouncerlessScrimController(mExecutor, mPowerManager);
        scrimController.addCallback(mCallback);
        final ShadeExpansionChangeEvent expansionEvent =
                new ShadeExpansionChangeEvent(0.5f, false, false, 0.0f);
        scrimController.expand(expansionEvent);
        mExecutor.runAllReady();
        verify(mCallback).onExpansion(eq(expansionEvent));
    }
}
