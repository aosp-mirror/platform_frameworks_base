/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.wm;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeast;

import android.content.Intent;
import android.platform.test.annotations.Presubmit;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for {@link WindowContainerTransaction}.
 *
 * Build/Install/Run:
 *  atest WmTests:WindowContainerTransactionTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class WindowContainerTransactionTests extends WindowTestsBase {

    @Test
    public void testRemoveTask() {
        final Task rootTask = createTask(mDisplayContent);
        final Task task = createTaskInRootTask(rootTask, 0 /* userId */);
        final ActivityRecord activity = createActivityRecord(mDisplayContent, task);

        WindowContainerTransaction wct = new WindowContainerTransaction();
        WindowContainerToken token = task.getTaskInfo().token;
        wct.removeTask(token);
        applyTransaction(wct);

        // There is still an activity to be destroyed, so the task is not removed immediately.
        assertNotNull(task.getParent());
        assertTrue(rootTask.hasChild());
        assertTrue(task.hasChild());
        assertTrue(activity.finishing);

        activity.destroyed("testRemoveContainer");
        // Assert that the container was removed after the activity is destroyed.
        assertNull(task.getParent());
        assertEquals(0, task.getChildCount());
        assertNull(activity.getParent());
        verify(mAtm.getLockTaskController(), atLeast(1)).clearLockedTask(task);
        verify(mAtm.getLockTaskController(), atLeast(1)).clearLockedTask(rootTask);
    }

    private Task createTask(int taskId) {
        return new Task.Builder(mAtm)
                .setTaskId(taskId)
                .setIntent(new Intent())
                .setRealActivity(ActivityBuilder.getDefaultComponent())
                .setEffectiveUid(10050)
                .buildInner();
    }

    private void applyTransaction(@NonNull WindowContainerTransaction t) {
        if (!t.isEmpty()) {
            mWm.mAtmService.mWindowOrganizerController.applyTransaction(t);
        }
    }
}
