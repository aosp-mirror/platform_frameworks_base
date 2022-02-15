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

package android.window;

import static org.junit.Assert.assertEquals;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class TaskFpsCallbackTest {

    private Context mContext;
    private WindowManager mWindowManager;
    private ActivityTaskManager mActivityTaskManager;

    @Before
    public void setup() {
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mActivityTaskManager = mContext.getSystemService(ActivityTaskManager.class);
        mWindowManager = mContext.getSystemService(WindowManager.class);
    }

    @Test
    public void testRegisterAndUnregister() {

        final TaskFpsCallback callback = new TaskFpsCallback() {
            @Override
            public void onFpsReported(float fps) {
                // Ignore
            }
        };
        final List<ActivityManager.RunningTaskInfo> tasks = mActivityTaskManager.getTasks(1);
        assertEquals(tasks.size(), 1);
        mWindowManager.registerTaskFpsCallback(tasks.get(0).taskId, Runnable::run, callback);
        mWindowManager.unregisterTaskFpsCallback(callback);
    }
}
