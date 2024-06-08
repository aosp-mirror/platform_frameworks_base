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
package com.android.internal.widget.remotecompose.core;

/**
 * Interface for operators that interact with variables
 * Threw this they register to listen to particular variables
 * and are notified when they change
 */
public interface VariableSupport {
    /**
     * Call to allow an operator to register interest in variables.
     * Typically they call context.listensTo(id, this)
     * @param context
     */
    void registerListening(RemoteContext context);

    /**
     * Called to be notified that the variables you are interested have changed.
     * @param context
     */
    void updateVariables(RemoteContext context);
}
