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

public class DrawOval extends PaintOperation {
    public static final Companion COMPANION = new Companion();
    float mLeft;
    float mTop;
    float mRight;
    float mBottom;


    public DrawOval(
            float left,
            float top,
            float right,
            float bottom) {
        mLeft = left;
        mTop = top;
        mRight = right;
        mBottom = bottom;
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mLeft, mTop, mRight, mBottom);
    }

    @Override
    public String toString() {
        return "DrawOval " + mLeft + " " + mTop
                + " " + mRight + " " + mBottom + ";";
    }

    public static class Companion implements CompanionOperation {
        private Companion() {
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            float sLeft = buffer.readFloat();
            float srcTop = buffer.readFloat();
            float srcRight = buffer.readFloat();
            float srcBottom = buffer.readFloat();

            DrawOval op = new DrawOval(sLeft, srcTop, srcRight, srcBottom);
            operations.add(op);
        }

        @Override
        public String name() {
            return "DrawOval";
        }

        @Override
        public int id() {
            return Operations.DRAW_OVAL;
        }

        public void apply(WireBuffer buffer,
                          float left,
                          float top,
                          float right,
                          float bottom) {
            buffer.start(Operations.DRAW_OVAL);
            buffer.writeFloat(left);
            buffer.writeFloat(top);
            buffer.writeFloat(right);
            buffer.writeFloat(bottom);
        }
    }

    @Override
    public void paint(PaintContext context) {
        context.drawOval(mLeft,
                mTop,
                mRight,
                mBottom);
    }
}
