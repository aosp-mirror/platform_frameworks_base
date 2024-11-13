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

import com.android.internal.widget.remotecompose.core.CompanionOperation;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

/**
 * Support modifier clip with a rectangle
 */
public class ClipRectModifierOperation extends DecoratorModifierOperation {

    public static final ClipRectModifierOperation.Companion COMPANION =
            new ClipRectModifierOperation.Companion();

    float mWidth;
    float mHeight;


    @Override
    public void paint(PaintContext context) {
        context.clipRect(0f, 0f, mWidth, mHeight);
    }

    @Override
    public void layout(RemoteContext context, float width, float height) {
        this.mWidth = width;
        this.mHeight = height;
    }

    @Override
    public void onClick(float x, float y) {
        // nothing
    }

    @Override
    public void serializeToString(int indent, StringSerializer serializer) {
        serializer.append(
                indent, "CLIP_RECT = [" + mWidth + ", " + mHeight + "]");
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer);
    }

    public static class Companion implements CompanionOperation {

        @Override
        public String name() {
            return "ClipRectModifier";
        }

        @Override
        public int id() {
            return Operations.MODIFIER_CLIP_RECT;
        }

        public void apply(WireBuffer buffer) {
            buffer.start(Operations.MODIFIER_CLIP_RECT);
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            operations.add(new ClipRectModifierOperation());
        }
    }
}
