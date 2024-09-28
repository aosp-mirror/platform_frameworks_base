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

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;

/**
 * Set the height dimension on a component
 */
public class HeightModifierOperation extends DimensionModifierOperation {

    public static final DimensionModifierOperation.Companion COMPANION =
            new DimensionModifierOperation.Companion(Operations.MODIFIER_HEIGHT, "WIDTH") {
                @Override
                public Operation construct(DimensionModifierOperation.Type type, float value) {
                    return new HeightModifierOperation(type, value);
                }
            };

    public HeightModifierOperation(Type type, float value) {
        super(type, value);
    }

    public HeightModifierOperation(Type type) {
        super(type);
    }

    public HeightModifierOperation(float value) {
        super(value);
    }

    @Override
    public String toString() {
        return "Height(" + mValue + ")";
    }

    @Override
    public String serializedName() {
        return "HEIGHT";
    }
}
