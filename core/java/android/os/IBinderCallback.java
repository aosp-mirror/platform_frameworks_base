/*
 * Copyright (C) 2023 The Android Open Source Project
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
 * Callback interface for binder transaction errors
 *
 * @hide
 */
public interface IBinderCallback {
    /**
     * Callback function for unexpected binder transaction errors.
     *
     * @param debugPid The binder transaction sender
     * @param code The binder transaction code
     * @param flags The binder transaction flags
     * @param err The binder transaction error
     */
    void onTransactionError(int debugPid, int code, int flags, int err);
}
