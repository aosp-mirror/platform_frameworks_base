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

public class DrawTweenPath extends PaintOperation {
    public static final Companion COMPANION = new Companion();
    float mTween;
    float mStart;
    float mStop;
    int mPath1Id;
    int mPath2Id;

    public DrawTweenPath(
            int path1Id,
            int path2Id,
            float tween,
            float start,
            float stop) {
        mTween = tween;
        mStart = start;
        mStop = stop;
        mPath1Id = path1Id;
        mPath2Id = path2Id;
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mPath1Id,
                mPath2Id,
                mTween,
                mStart,
                mStop);
    }

    @Override
    public String toString() {
        return "DrawTweenPath " + mPath1Id + " " + mPath2Id
                + " " + mTween + " " + mStart + " "
                + "- " + mStop;
    }

    public static class Companion implements CompanionOperation {
        private Companion() {
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            int path1Id = buffer.readInt();
            int path2Id = buffer.readInt();
            float tween = buffer.readFloat();
            float start = buffer.readFloat();
            float stop = buffer.readFloat();
            DrawTweenPath op = new DrawTweenPath(path1Id, path2Id,
                    tween, start, stop);
            operations.add(op);
        }

        @Override
        public String name() {
            return "DrawTweenPath";
        }

        @Override
        public int id() {
            return Operations.DRAW_TWEEN_PATH;
        }

        public void apply(WireBuffer buffer,
                          int path1Id,
                          int path2Id,
                          float tween,
                          float start,
                          float stop) {
            buffer.start(Operations.DRAW_TWEEN_PATH);
            buffer.writeInt(path1Id);
            buffer.writeInt(path2Id);
            buffer.writeFloat(tween);
            buffer.writeFloat(start);
            buffer.writeFloat(stop);
        }
    }

    @Override
    public void paint(PaintContext context) {
        context.drawTweenPath(mPath1Id,
                mPath2Id,
                mTween,
                mStart,
                mStop);
    }

}
