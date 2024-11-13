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
package com.android.internal.widget.remotecompose.core.operations.layout.modifiers;

import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.operations.layout.DecoratorComponent;

/**
 * Represents a decorator modifier (lightweight component), ie a modifier
 * that impacts the visual output (background, border...)
 */
public abstract class DecoratorModifierOperation extends PaintOperation
        implements ModifierOperation, DecoratorComponent {

    @Override
    public void onClick(float x, float y) {
        // nothing
    }
}
