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

package com.android.server.policy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.KeyEvent;

import org.junit.Before;
import org.junit.Test;

/**
 * Test class for {@link DeferredKeyActionExecutor}.
 *
 * <p>Build/Install/Run: atest WmTests:DeferredKeyActionExecutorTests
 */
public final class DeferredKeyActionExecutorTests {

    private DeferredKeyActionExecutor mKeyActionExecutor;

    @Before
    public void setUp() {
        mKeyActionExecutor = new DeferredKeyActionExecutor();
    }

    @Test
    public void queueKeyAction_actionNotExecuted() {
        TestAction action = new TestAction();

        mKeyActionExecutor.queueKeyAction(KeyEvent.KEYCODE_STEM_PRIMARY, /* downTime= */ 1, action);

        assertFalse(action.executed);
    }

    @Test
    public void setActionsExecutable_afterActionQueued_actionExecuted() {
        TestAction action = new TestAction();
        mKeyActionExecutor.queueKeyAction(KeyEvent.KEYCODE_STEM_PRIMARY, /* downTime= */ 1, action);

        mKeyActionExecutor.setActionsExecutable(KeyEvent.KEYCODE_STEM_PRIMARY, /* downTime= */ 1);

        assertTrue(action.executed);
    }

    @Test
    public void queueKeyAction_alreadyExecutable_actionExecuted() {
        TestAction action = new TestAction();
        mKeyActionExecutor.setActionsExecutable(KeyEvent.KEYCODE_STEM_PRIMARY, /* downTime= */ 1);

        mKeyActionExecutor.queueKeyAction(KeyEvent.KEYCODE_STEM_PRIMARY, /* downTime= */ 1, action);

        assertTrue(action.executed);
    }

    @Test
    public void setActionsExecutable_afterActionQueued_downTimeMismatch_actionNotExecuted() {
        TestAction action1 = new TestAction();
        mKeyActionExecutor.queueKeyAction(
                KeyEvent.KEYCODE_STEM_PRIMARY, /* downTime= */ 1, action1);

        mKeyActionExecutor.setActionsExecutable(KeyEvent.KEYCODE_STEM_PRIMARY, /* downTime= */ 2);

        assertFalse(action1.executed);

        TestAction action2 = new TestAction();
        mKeyActionExecutor.queueKeyAction(
                KeyEvent.KEYCODE_STEM_PRIMARY, /* downTime= */ 2, action2);

        assertFalse(action1.executed);
        assertTrue(action2.executed);
    }

    @Test
    public void queueKeyAction_afterSetExecutable_downTimeMismatch_actionNotExecuted() {
        TestAction action = new TestAction();
        mKeyActionExecutor.setActionsExecutable(KeyEvent.KEYCODE_STEM_PRIMARY, /* downTime= */ 1);

        mKeyActionExecutor.queueKeyAction(KeyEvent.KEYCODE_STEM_PRIMARY, /* downTime= */ 2, action);

        assertFalse(action.executed);
    }

    static class TestAction implements Runnable {
        public boolean executed;

        @Override
        public void run() {
            executed = true;
        }
    }
}
