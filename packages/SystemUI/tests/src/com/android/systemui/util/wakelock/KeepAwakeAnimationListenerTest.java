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

package com.android.systemui.util.wakelock;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.animation.Animator;
import android.os.Looper;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner.class)
public class KeepAwakeAnimationListenerTest extends SysuiTestCase {
    @Mock WakeLock mWakeLock;
    KeepAwakeAnimationListener mKeepAwakeAnimationListener;

    @Before
    public void setup() {
        Assert.sMainLooper = TestableLooper.get(this).getLooper();
        MockitoAnnotations.initMocks(this);
        KeepAwakeAnimationListener.sWakeLock = mWakeLock;
        mKeepAwakeAnimationListener = new KeepAwakeAnimationListener(getContext());
    }

    @Test
    public void onAnimationStart_holdsWakeLock() {
        mKeepAwakeAnimationListener.onAnimationStart((Animator) null);
        verify(mWakeLock).acquire(anyString());
        verify(mWakeLock, never()).release(anyString());

        mKeepAwakeAnimationListener.onAnimationEnd((Animator) null);
        verify(mWakeLock).release(anyString());
    }

    @Test(expected = IllegalStateException.class)
    public void initThrows_onNonMainThread() {
        Assert.sMainLooper = Looper.getMainLooper();
        new KeepAwakeAnimationListener(getContext());
    }
}
