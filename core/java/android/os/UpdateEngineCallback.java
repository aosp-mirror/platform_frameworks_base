/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.os;

import android.annotation.SystemApi;

/**
 * Callback function for UpdateEngine.
 *
 * The APIs defined in this class and UpdateEngine class must be in sync with
 * the ones in
 * system/update_engine/binder_bindings/android/os/IUpdateEngine.aidl and
 * system/update_engine/binder_bindings/android/os/IUpdateEngineCallback.aidl.
 *
 * {@hide}
 */
@SystemApi
public abstract class UpdateEngineCallback {

    @SystemApi
    public abstract void onStatusUpdate(int status, float percent);

    @SystemApi
    public abstract void onPayloadApplicationComplete(int errorCode);
}
