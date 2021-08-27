/**
 * Copyright (c) 2020, The Android Open Source Project
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

package android.tracing;

/**
 * Binder interface for the TracingServiceProxy running in system_server.
 *
 * {@hide}
 */
interface ITracingServiceProxy
{
    /**
     * Notifies system tracing app that a tracing session has ended. If a session is repurposed
     * for use in a bugreport, sessionStolen can be set to indicate that tracing has ended but
     * there is no buffer available to dump.
     */
    oneway void notifyTraceSessionEnded(boolean sessionStolen);
}
