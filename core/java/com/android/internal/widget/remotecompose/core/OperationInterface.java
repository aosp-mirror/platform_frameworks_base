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
package com.android.internal.widget.remotecompose.core;

import android.annotation.NonNull;

/** Base interface for RemoteCompose operations */
public interface OperationInterface {

    /** add the operation to the buffer */
    void write(@NonNull WireBuffer buffer);

    /**
     * paint an operation
     *
     * @param context the paint context used to paint the operation
     */
    void apply(@NonNull RemoteContext context);

    /** Debug utility to display an operation + indentation */
    @NonNull
    String deepToString(@NonNull String indent);

    /**
     * Returns true if the operation is marked as "dirty"
     *
     * @return true if dirty
     */
    boolean isDirty();

    /** Mark the operation as "dirty" to indicate it will need to be re-executed. */
    void markNotDirty();
}
