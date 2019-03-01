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

package com.android.systemui;

import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertTrue;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class InitControllerTest extends SysuiTestCase {

    private InitController mInitController = new InitController();

    @Test
    public void testInitControllerExecutesTasks() {
        boolean[] runs = {false, false, false};
        mInitController.addPostInitTask(() -> {
            runs[0] = true;
        });
        mInitController.addPostInitTask(() -> {
            runs[1] = true;
        });
        mInitController.addPostInitTask(() -> {
            runs[2] = true;
        });
        assertFalse(runs[0] || runs[1] || runs[2]);

        mInitController.executePostInitTasks();
        assertTrue(runs[0] && runs[1] && runs[2]);
    }

    @Test(expected = IllegalStateException.class)
    public void testInitControllerThrowsWhenTasksAreAddedAfterExecution() {
        boolean[] runs = {false, false, false};
        mInitController.addPostInitTask(() -> {
            runs[0] = true;
        });
        mInitController.addPostInitTask(() -> {
            runs[1] = true;
        });

        mInitController.executePostInitTasks();

        // Throws
        mInitController.addPostInitTask(() -> {
            runs[2] = true;
        });
    }
}
