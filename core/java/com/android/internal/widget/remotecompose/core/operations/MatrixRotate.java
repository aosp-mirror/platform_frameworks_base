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

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.PaintContext;

public class MatrixRotate extends DrawBase3 {
    public static final Companion COMPANION =
            new Companion(Operations.MATRIX_ROTATE) {
                @Override
                public Operation construct(float rotate,
                                           float pivotX,
                                           float pivotY
                ) {
                    return new MatrixRotate(rotate, pivotX, pivotY);
                }
            };

    public MatrixRotate(float rotate, float pivotX, float pivotY) {
        super(rotate, pivotX, pivotY);
        mName = "MatrixRotate";
    }

    @Override
    public void paint(PaintContext context) {
        context.matrixRotate(mV1, mV2, mV3);
    }
}
