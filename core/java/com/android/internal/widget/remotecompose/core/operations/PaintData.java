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
import com.android.internal.widget.remotecompose.core.operations.paint.PaintBundle;

import java.util.List;

public class PaintData extends PaintOperation {
    public PaintBundle mPaintData = new PaintBundle();
    public static final Companion COMPANION = new Companion();
    public static final int MAX_STRING_SIZE = 4000;

    public PaintData() {
    }

    @Override
    public void write(WireBuffer buffer) {
        COMPANION.apply(buffer, mPaintData);
    }

    @Override
    public String toString() {
        return "PaintData " + "\"" + mPaintData + "\"";
    }

    public static class Companion implements CompanionOperation {
        private Companion() {
        }

        @Override
        public String name() {
            return "TextData";
        }

        @Override
        public int id() {
            return Operations.PAINT_VALUES;
        }

        public void apply(WireBuffer buffer, PaintBundle paintBundle) {
            buffer.start(Operations.PAINT_VALUES);
            paintBundle.writeBundle(buffer);
        }

        @Override
        public void read(WireBuffer buffer, List<Operation> operations) {
            PaintData data = new PaintData();
            data.mPaintData.readBundle(buffer);
            operations.add(data);
        }
    }

    @Override
    public String deepToString(String indent) {
        return indent + toString();
    }

    @Override
    public void paint(PaintContext context) {
        context.applyPaint(mPaintData);
    }

}
