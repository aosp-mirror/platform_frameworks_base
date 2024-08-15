/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.wm.shell.compatui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.wm.shell.ShellTestCase;


import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * Tests for {@link CompatUILayout}.
 *
 * Build/Install/Run:
 *  atest WMShellUnitTests:CompatUIStatusManagerTest
 */
@RunWith(AndroidTestingRunner.class)
@SmallTest
public class CompatUIStatusManagerTest extends ShellTestCase {

    private FakeCompatUIStatusManagerTest mTestState;
    private CompatUIStatusManager mStatusManager;

    @Before
    public void setUp() {
        mTestState = new FakeCompatUIStatusManagerTest();
        mStatusManager = new CompatUIStatusManager(mTestState.mWriter, mTestState.mReader);
    }

    @Test
    public void isEducationShown() {
        assertFalse(mStatusManager.isEducationVisible());

        mStatusManager.onEducationShown();
        assertTrue(mStatusManager.isEducationVisible());

        mStatusManager.onEducationHidden();
        assertFalse(mStatusManager.isEducationVisible());
    }

    static class FakeCompatUIStatusManagerTest {

        int mCurrentStatus = 0;

        final IntSupplier mReader = () -> mCurrentStatus;

        final IntConsumer mWriter = newStatus -> mCurrentStatus = newStatus;

    }
}
