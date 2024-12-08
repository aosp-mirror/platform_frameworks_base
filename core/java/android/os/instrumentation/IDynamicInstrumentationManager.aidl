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

package android.os.instrumentation;

import android.os.instrumentation.IOffsetCallback;
import android.os.instrumentation.MethodDescriptor;
import android.os.instrumentation.TargetProcess;

/**
 * System private API for managing the dynamic attachment of instrumentation.
 *
 * {@hide}
 */
interface IDynamicInstrumentationManager {
    /** Provides ART metadata about the described compiled method within the target process */
    @PermissionManuallyEnforced
    void getExecutableMethodFileOffsets(
            in TargetProcess targetProcess, in MethodDescriptor methodDescriptor,
            in IOffsetCallback callback);
}
