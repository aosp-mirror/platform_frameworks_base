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
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;

import java.util.List;

/**
 * Represents a padding modifier.
 * Padding modifiers can be chained and will impact following modifiers.
 */
public class PaddingModifierOperation implements ModifierOperation {

    public static final PaddingModifierOperation.Companion COMPANION =
            new PaddingModifierOperation.Companion();

    float mLeft;
    float mTop;
    float mRight;
    float mBottom;

    public PaddingModifierOperation(float left, float top, float right, float bottom) {
        this.mLeft = left;
        this.mTop = top;
        this.mRight = right;
        this.mBottom = bottom;
    }

    public float getLeft() {
        return mLeft;
    }

    public float getTop() {
        return mTop;
    }

    public float getRight() {
        return mRight;
    }

    public float getBottom() {
        return mBottom;
    }

    public void setLeft(float left) {
        this.mLeft = left;
    }

    public void setTop(float top) {
        this.mTop = top;
    }

    public void setRight(float right) {
        this.mRight = right;
    }

    public void setBottom(float bottom) {
        this.mBottom = bottom;
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mLeft, mTop, mRight, mBottom);
    }

    @Override
    public void serializeToString(int indent, StringSerializer serializer) {
        serializer.append(indent, "PADDING = [" + mLeft + ", " + mTop + ", "
                + mRight + ", " + mBottom + "]");
    }

    @Override
    public void apply(RemoteContext context) {
    }

    @Override
    public String deepToString(String indent) {
        return (indent != null ? indent : "") + toString();
    }

    @Override
    public String toString() {
        return "PaddingModifierOperation(" + mLeft + ", " + mTop
                + ", " + mRight + ", " + mBottom + ")";
    }

    public static class Companion implements CompanionOperation {
        @Override
        public String name() {
            return "PaddingModifierOperation";
        }

        @Override
        public int id() {
            return Operations.MODIFIER_PADDING;
        }

        public void apply(WireBuffer buffer,
                                 float left, float top, float right, float bottom) {
            buffer.start(Operations.MODIFIER_PADDING);
            buffer.writeFloat(left);
            buffer.writeFloat(top);
            buffer.writeFloat(right);
            buffer.writeFloat(bottom);
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            float left = buffer.readFloat();
            float top = buffer.readFloat();
            float right = buffer.readFloat();
            float bottom = buffer.readFloat();
            operations.add(new PaddingModifierOperation(left, top, right, bottom));
        }
    }
}
