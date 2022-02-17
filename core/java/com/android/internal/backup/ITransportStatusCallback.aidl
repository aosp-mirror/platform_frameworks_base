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

package com.android.internal.backup;

/**
* A callback class for {@link IBackupTransport}
*
* {@hide}
*/
oneway interface ITransportStatusCallback {
    /**
    * Callback for methods that complete with an {@code int} status.
    */
    void onOperationCompleteWithStatus(int status);

    /**
    * Callback for methods that complete without a value.
    */
    void onOperationComplete();
}
