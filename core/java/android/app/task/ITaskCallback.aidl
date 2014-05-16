/**
 * Copyright 2014, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.app.task;

import android.app.task.ITaskService;
import android.app.task.TaskParams;

/**
 * The server side of the TaskManager IPC protocols.  The app-side implementation
 * invokes on this interface to indicate completion of the (asynchronous) instructions
 * issued by the server.
 *
 * In all cases, the 'who' parameter is the caller's service binder, used to track
 * which Task Service instance is reporting.
 *
 * {@hide}
 */
interface ITaskCallback {
    /**
     * Immediate callback to the system after sending a start signal, used to quickly detect ANR.
     *
     * @param taskId Unique integer used to identify this task.
     * @param ongoing True to indicate that the client is processing the task. False if the task is
     * complete
     */
    void acknowledgeStartMessage(int taskId, boolean ongoing);
    /**
     * Immediate callback to the system after sending a stop signal, used to quickly detect ANR.
     *
     * @param taskId Unique integer used to identify this task.
     * @param rescheulde Whether or not to reschedule this task.
     */
    void acknowledgeStopMessage(int taskId, boolean reschedule);
    /*
     * Tell the task manager that the client is done with its execution, so that it can go on to
     * the next one and stop attributing wakelock time to us etc.
     *
     * @param taskId Unique integer used to identify this task.
     * @param reschedule Whether or not to reschedule this task.
     */
    void taskFinished(int taskId, boolean reschedule);
}
