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

public class MatrixSkew extends PaintOperation {
    public static final Companion COMPANION = new Companion();
    float mSkewX, mSkewY;

    public MatrixSkew(float skewX, float skewY) {
        mSkewX = skewX;
        mSkewY = skewY;
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mSkewX, mSkewY);
    }

    @Override
    public String toString() {
        return "DrawArc " + mSkewY + ", " + mSkewY + ";";
    }

    public static class Companion implements CompanionOperation {
        private Companion() {
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            float skewX = buffer.readFloat();
            float skewY = buffer.readFloat();
            MatrixSkew op = new MatrixSkew(skewX, skewY);
            operations.add(op);
        }

        @Override
        public String name() {
            return "Matrix";
        }

        @Override
        public int id() {
            return Operations.MATRIX_SKEW;
        }

        public void apply(WireBuffer buffer, float skewX, float skewY) {
            buffer.start(Operations.MATRIX_SKEW);
            buffer.writeFloat(skewX);
            buffer.writeFloat(skewY);
        }
    }

    @Override
    public void paint(PaintContext context) {
        context.matrixSkew(mSkewX, mSkewY);
    }
}
