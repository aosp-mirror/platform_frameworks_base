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

public class DrawRoundRect extends PaintOperation {
    public static final Companion COMPANION = new Companion();
    float mLeft;
    float mTop;
    float mRight;
    float mBottom;
    float mRadiusX;
    float mRadiusY;

    public DrawRoundRect(
            float left,
            float top,
            float right,
            float bottom,
            float radiusX,
            float radiusY) {
        mLeft = left;
        mTop = top;
        mRight = right;
        mBottom = bottom;
        mRadiusX = radiusX;
        mRadiusY = radiusY;
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mLeft, mTop, mRight, mBottom, mRadiusX, mRadiusY);
    }

    @Override
    public String toString() {
        return "DrawRoundRect " + mLeft + " " + mTop
                + " " + mRight + " " + mBottom
                + " (" + mRadiusX + " " + mRadiusY + ");";
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
            float srcRadiusX = buffer.readFloat();
            float srcRadiusY = buffer.readFloat();

            DrawRoundRect op = new DrawRoundRect(sLeft, srcTop, srcRight,
                    srcBottom, srcRadiusX, srcRadiusY);
            operations.add(op);
        }

        @Override
        public String name() {
            return "DrawOval";
        }

        @Override
        public int id() {
            return Operations.DRAW_ROUND_RECT;
        }

        public void apply(WireBuffer buffer,
                          float left,
                          float top,
                          float right,
                          float bottom,
                          float radiusX,
                          float radiusY) {
            buffer.start(Operations.DRAW_ROUND_RECT);
            buffer.writeFloat(left);
            buffer.writeFloat(top);
            buffer.writeFloat(right);
            buffer.writeFloat(bottom);
            buffer.writeFloat(radiusX);
            buffer.writeFloat(radiusY);
        }
    }

    @Override
    public void paint(PaintContext context) {
        context.drawRoundRect(mLeft,
                mTop,
                mRight,
                mBottom,
                mRadiusX,
                mRadiusY
        );
    }

}
