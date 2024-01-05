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

package android.os;

/**
 * Callback function for UpdateEngineStable. Used to keep the caller up to date with progress, so
 * the UI (if any) can be updated.
 *
 * <p>The APIs defined in this class and UpdateEngineStable class must be in sync with the ones in
 * system/update_engine/stable/android/os/IUpdateEngineStable.aidl and
 * system/update_engine/stable/android/os/IUpdateEngineStableCallback.aidl.
 *
 * <p>{@hide}
 */
public abstract class UpdateEngineStableCallback {

    /**
     * Invoked when anything changes. The value of {@code status} will be one of the values from
     * {@link UpdateEngine.UpdateStatusConstants}, and {@code percent} will be valid
     *
     * @hide
     */
    public abstract void onStatusUpdate(int status, float percent);

    /**
     * Invoked when the payload has been applied, whether successfully or unsuccessfully. The value
     * of {@code errorCode} will be one of the values from {@link UpdateEngine.ErrorCodeConstants}.
     *
     * @hide
     */
    public abstract void onPayloadApplicationComplete(@UpdateEngineStable.ErrorCode int errorCode);
}
