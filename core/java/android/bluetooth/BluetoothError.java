/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.bluetooth;

/**
 * Bluetooth API error codes.
 *
 * Errors are always negative.
 *
 * @hide
 */
public class BluetoothError {
    /** No error */
    public static final int SUCCESS = 0;

    /** Generic error */
    public static final int ERROR = -1000;

    /** Bluetooth currently disabled */
    public static final int ERROR_DISABLED = -1001;

    /** IPC is not ready, for example service is not yet bound */
    public static final int ERROR_IPC_NOT_READY = -1011;

    /** Some other IPC error, for example a RemoteException */
    public static final int ERROR_IPC = -1012;

}
