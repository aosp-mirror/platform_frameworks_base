/**
 * Copyright (c) 2019, The Android Open Source Project
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

package android.net;

/**
 * Callback to provide status changes of keepalive offload.
 *
 * @hide
 */
oneway interface ISocketKeepaliveCallback
{
    /** The keepalive was successfully started. */
    void onStarted(int slot);
    /** The keepalive was successfully stopped. */
    void onStopped();
    /** The keepalive was stopped because of an error. */
    void onError(int error);
    /** The keepalive on a TCP socket was stopped because the socket received data. */
    void onDataReceived();
}
