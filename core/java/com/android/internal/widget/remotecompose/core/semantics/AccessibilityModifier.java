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
package com.android.internal.widget.remotecompose.core.semantics;

import com.android.internal.widget.remotecompose.core.operations.layout.modifiers.ModifierOperation;

/**
 * A Modifier that provides semantic info.
 *
 * <p>This is needed since `AccessibilityModifier` is generally an open set and designed to be
 * extended.
 */
public interface AccessibilityModifier extends ModifierOperation, AccessibleComponent {
    /**
     * This method retrieves the operation code.
     *
     * <p>This function is used to get the current operation code associated with the object or
     * context this method belongs to.
     *
     * @return The operation code as an integer.
     */
    int getOpCode();
}
