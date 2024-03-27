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

public class DrawCircle extends PaintOperation {
    public static final Companion COMPANION = new Companion();
    float mCenterX;
    float mCenterY;
    float mRadius;

    public DrawCircle(float centerX, float centerY, float radius) {
        mCenterX = centerX;
        mCenterY = centerY;
        mRadius = radius;
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mCenterX,
                mCenterY,
                mRadius);
    }

    @Override
    public String toString() {
        return "";
    }

    public static class Companion implements CompanionOperation {
        private Companion() {
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            float centerX = buffer.readFloat();
            float centerY = buffer.readFloat();
            float radius = buffer.readFloat();

            DrawCircle op = new DrawCircle(centerX, centerY, radius);
            operations.add(op);
        }

        @Override
        public String name() {
            return "";
        }

        @Override
        public int id() {
            return 0;
        }

        public void apply(WireBuffer buffer, float centerX, float centerY, float radius) {
            buffer.start(Operations.DRAW_CIRCLE);
            buffer.writeFloat(centerX);
            buffer.writeFloat(centerY);
            buffer.writeFloat(radius);
        }
    }

    @Override
    public void paint(PaintContext context) {
        context.drawCircle(mCenterX,
                mCenterY,
                mRadius);
    }
}
