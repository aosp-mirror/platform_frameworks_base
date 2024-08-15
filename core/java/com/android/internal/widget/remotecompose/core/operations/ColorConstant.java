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
package com.android.internal.widget.remotecompose.core.operations;

import com.android.internal.widget.remotecompose.core.CompanionOperation;
import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.WireBuffer;

import java.util.List;

/**
 * Operation that defines a simple Color based on ID
 * Mainly for colors in theming.
 */
public class ColorConstant implements Operation {
    public int mColorId;
    public int mColor;
    public static final Companion COMPANION = new Companion();

    public ColorConstant(int colorId, int color) {
        this.mColorId = colorId;
        this.mColor = color;
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mColorId, mColor);
    }

    @Override
    public String toString() {
        return "ColorConstant[" + mColorId + "] = " + Utils.colorInt(mColor) + "";
    }

    public static class Companion implements CompanionOperation {
        private Companion() {
        }

        @Override
        public String name() {
            return "ColorConstant";
        }

        @Override
        public int id() {
            return Operations.COLOR_CONSTANT;
        }

        /**
         * Writes out the operation to the buffer
         *
         * @param buffer
         * @param colorId
         * @param color
         */
        public void apply(WireBuffer buffer, int colorId, int color) {
            buffer.start(Operations.COLOR_CONSTANT);
            buffer.writeInt(colorId);
            buffer.writeInt(color);
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            int colorId = buffer.readInt();
            int color = buffer.readInt();
            operations.add(new ColorConstant(colorId, color));
        }
    }

    @Override
    public void apply(RemoteContext context) {
        context.loadColor(mColorId, mColor);
    }

    @Override
    public String deepToString(String indent) {
        return indent + toString();
    }
}
