/*
 * Copyright (C) 2014 The Android Open Source Project
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

// in android.app so ContextImpl has package access
package android.app;

import android.app.task.ITaskManager;
import android.app.task.Task;
import android.app.task.TaskManager;
import android.os.RemoteException;

import java.util.List;


/**
 * Concrete implementation of the TaskManager interface
 * @hide 
 */
public class TaskManagerImpl extends TaskManager {
    ITaskManager mBinder;

    /* package */ TaskManagerImpl(ITaskManager binder) {
        mBinder = binder;
    }

    @Override
    public int schedule(Task task) {
        try {
            return mBinder.schedule(task);
        } catch (RemoteException e) {
            return TaskManager.RESULT_FAILURE;
        }
    }

    @Override
    public void cancel(int taskId) {
        try {
            mBinder.cancel(taskId);
        } catch (RemoteException e) {}

    }

    @Override
    public void cancelAll() {
        try {
            mBinder.cancelAll();
        } catch (RemoteException e) {}

    }

    @Override
    public List<Task> getAllPendingTasks() {
        try {
            return mBinder.getAllPendingTasks();
        } catch (RemoteException e) {
            return null;
        }
    }
}
