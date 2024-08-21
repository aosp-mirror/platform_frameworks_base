/*
 * Copyright (C) 2024 The Android Open Source Project
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

package android.app.appfunctions;

import android.os.Bundle;
import android.app.appfunctions.IExecuteAppFunctionCallback;
import android.app.appfunctions.ExecuteAppFunctionRequest;


/**
 * Defines the interface for the system server to request the execution of an app function within
 * the app process.
 *
 * This interface is implemented by the app and exposed to the system server via a {@code Service}.
 *
 * @hide
 */
oneway interface IAppFunctionService {
    /**
     * Called by the system to execute a specific app function.
     *
     * @param request  the function execution request.
     * @param callback a callback to report back the result.
     */
    void executeAppFunction(
        in ExecuteAppFunctionRequest request,
        in IExecuteAppFunctionCallback callback
    );
}
