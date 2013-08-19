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

package android.app.maintenance;

import android.app.maintenance.IIdleService;

/**
 * The server side of the idle maintenance IPC protocols.  The app-side implementation
 * invokes on this interface to indicate completion of the (asynchronous) instructions
 * issued by the server.
 *
 * In all cases, the 'who' parameter is the caller's service binder, used to track
 * which idle service instance is reporting.
 *
 * {@hide}
 */
interface IIdleCallback {
    /**
     * Acknowledge receipt and processing of the asynchronous "start idle work" incall.
     * 'result' is true if the app wants some time to perform ongoing background
     * idle-time work; or false if the app declares that it does not need any time
     * for such work.
     */
    void acknowledgeStart(int token, boolean result);

    /**
     * Acknowledge receipt and processing of the asynchronous "stop idle work" incall.
     */
    void acknowledgeStop(int token);

    /*
     * Tell the idle service manager that we're done with our idle maintenance, so that
     * it can go on to the next one and stop attributing wakelock time to us etc.
     *
     * @param opToken The identifier passed in the startIdleMaintenance() call that
     *        indicated the beginning of this service's idle timeslice.
     */
    void idleFinished(int token);
}
