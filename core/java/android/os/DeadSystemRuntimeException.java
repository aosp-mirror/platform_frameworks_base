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

package android.os;

/**
 * Exception thrown when a call into system_server resulted in a
 * DeadObjectException, meaning that the system_server has died or
 * experienced a low-level binder error.  There's * nothing apps can
 * do at this point - the system will automatically restart - so
 * there's no point in catching this.
 *
 * @hide
 */
public class DeadSystemRuntimeException extends RuntimeException {
    public DeadSystemRuntimeException() {
        super(new DeadSystemException());
    }
}
