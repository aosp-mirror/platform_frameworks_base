/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.keyguard.clock;

import static com.google.common.truth.Truth.assertThat;

import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.LeakCheck;
import android.testing.TestableLooper.RunWithLooper;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dock.DockManager;
import com.android.systemui.dock.DockManagerFake;
import com.android.systemui.utils.leaks.FakeExtensionController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public final class ClockManagerTest extends SysuiTestCase {

    private ClockManager mClockManager;
    private LeakCheck mLeakCheck;
    private FakeExtensionController mFakeExtensionController;
    private DockManagerFake mFakeDockManager;
    @Mock ClockManager.ClockChangedListener mMockListener;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mLeakCheck = new LeakCheck();
        mFakeExtensionController = new FakeExtensionController(mLeakCheck);
        mFakeDockManager = new DockManagerFake();
        mClockManager = new ClockManager(getContext(), mFakeExtensionController,
                mFakeDockManager);
        mClockManager.addOnClockChangedListener(mMockListener);
    }

    @After
    public void tearDown() {
        mClockManager.removeOnClockChangedListener(mMockListener);
    }

    @Test
    public void dockEvent() {
        mFakeDockManager.setDockEvent(DockManager.STATE_DOCKED);
        assertThat(mClockManager.isDocked()).isTrue();
    }

    @Test
    public void undockEvent() {
        mFakeDockManager.setDockEvent(DockManager.STATE_NONE);
        assertThat(mClockManager.isDocked()).isFalse();
    }
}
