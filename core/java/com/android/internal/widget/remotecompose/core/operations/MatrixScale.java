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

public class MatrixScale extends PaintOperation {
    public static final Companion COMPANION = new Companion();
    float mScaleX, mScaleY;
    float mCenterX, mCenterY;

    public MatrixScale(float scaleX, float scaleY, float centerX, float centerY) {
        mScaleX = scaleX;
        mScaleY = scaleY;
        mCenterX = centerX;
        mCenterY = centerY;
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mScaleX, mScaleY, mCenterX, mCenterY);
    }

    @Override
    public String toString() {
        return "MatrixScale " + mScaleY + ", " + mScaleY + ";";
    }

    public static class Companion implements CompanionOperation {
        private Companion() {
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            float scaleX = buffer.readFloat();
            float scaleY = buffer.readFloat();
            float centerX = buffer.readFloat();
            float centerY = buffer.readFloat();
            MatrixScale op = new MatrixScale(scaleX, scaleY, centerX, centerY);
            operations.add(op);
        }

        @Override
        public String name() {
            return "Matrix";
        }

        @Override
        public int id() {
            return Operations.MATRIX_SCALE;
        }

        public void apply(WireBuffer buffer, float scaleX, float scaleY,
                float centerX, float centerY) {
            buffer.start(Operations.MATRIX_SCALE);
            buffer.writeFloat(scaleX);
            buffer.writeFloat(scaleY);
            buffer.writeFloat(centerX);
            buffer.writeFloat(centerY);

        }
    }

    @Override
    public void paint(PaintContext context) {
        context.mtrixScale(mScaleX, mScaleY, mCenterX, mCenterY);
    }
}
