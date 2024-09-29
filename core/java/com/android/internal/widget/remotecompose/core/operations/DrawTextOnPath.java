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
import com.android.internal.widget.remotecompose.core.PaintContext;
import com.android.internal.widget.remotecompose.core.PaintOperation;
import com.android.internal.widget.remotecompose.core.WireBuffer;

import java.util.List;

/**
 * Draw text along a path.
 */
public class DrawTextOnPath extends PaintOperation {
    public static final Companion COMPANION = new Companion();
    int mPathId;
    public int mTextId;
    float mVOffset;
    float mHOffset;

    public DrawTextOnPath(int textId, int pathId, float hOffset, float vOffset) {
        mPathId = pathId;
        mTextId = textId;
        mHOffset = vOffset;
        mVOffset = hOffset;
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mTextId, mPathId, mHOffset, mVOffset);
    }

    @Override
    public String toString() {
        return "DrawTextOnPath [" + mTextId + "] [" + mPathId + "] "
                + mHOffset + ", " + mVOffset;
    }

    public static class Companion implements CompanionOperation {
        private Companion() {
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            int textId = buffer.readInt();
            int pathId = buffer.readInt();
            float hOffset = buffer.readFloat();
            float vOffset = buffer.readFloat();
            DrawTextOnPath op = new DrawTextOnPath(textId, pathId, hOffset, vOffset);
            operations.add(op);
        }

        @Override
        public String name() {
            return "DrawTextOnPath";
        }

        @Override
        public int id() {
            return Operations.DRAW_TEXT_ON_PATH;
        }

        public void apply(WireBuffer buffer, int textId, int pathId, float hOffset, float vOffset) {
            buffer.start(Operations.DRAW_TEXT_ON_PATH);
            buffer.writeInt(textId);
            buffer.writeInt(pathId);
            buffer.writeFloat(hOffset);
            buffer.writeFloat(vOffset);
        }
    }

    @Override
    public void paint(PaintContext context) {
        context.drawTextOnPath(mTextId, mPathId, mHOffset, mVOffset);
    }
}
