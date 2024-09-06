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
 * Defines a path that clips a the subsequent drawing commands
 * Use MatrixSave and MatrixRestore commands to remove clip
 * TODO allow id 0 to mean null?
 */
public class ClipPath extends PaintOperation {
    public static final Companion COMPANION = new Companion();
    int mId;
    int mRegionOp;

    public ClipPath(int pathId, int regionOp) {
        mId = pathId;
        mRegionOp = regionOp;
    }

    public static final int REPLACE = Companion.PATH_CLIP_REPLACE;
    public static final int DIFFERENCE = Companion.PATH_CLIP_DIFFERENCE;
    public static final int INTERSECT = Companion.PATH_CLIP_INTERSECT;
    public static final int UNION = Companion.PATH_CLIP_UNION;
    public static final int XOR = Companion.PATH_CLIP_XOR;
    public static final int REVERSE_DIFFERENCE = Companion.PATH_CLIP_REVERSE_DIFFERENCE;
    public static final int UNDEFINED = Companion.PATH_CLIP_UNDEFINED;

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mId);
    }

    @Override
    public String toString() {
        return "ClipPath " + mId + ";";
    }

    public static class Companion implements CompanionOperation {
        public static final int PATH_CLIP_REPLACE = 0;
        public static final int PATH_CLIP_DIFFERENCE = 1;
        public static final int PATH_CLIP_INTERSECT = 2;
        public static final int PATH_CLIP_UNION = 3;
        public static final int PATH_CLIP_XOR = 4;
        public static final int PATH_CLIP_REVERSE_DIFFERENCE = 5;
        public static final int PATH_CLIP_UNDEFINED = 6;

        private Companion() {
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            int pack = buffer.readInt();
            int id = pack & 0xFFFFF;
            int regionOp = pack >> 24;
            ClipPath op = new ClipPath(id, regionOp);
            operations.add(op);
        }

        @Override
        public String name() {
            return "ClipPath";
        }

        @Override
        public int id() {
            return Operations.CLIP_PATH;
        }

        public void apply(WireBuffer buffer, int id) {
            buffer.start(Operations.CLIP_PATH);
            buffer.writeInt(id);
        }
    }

    @Override
    public void paint(PaintContext context) {
        context.clipPath(mId, mRegionOp);
    }
}