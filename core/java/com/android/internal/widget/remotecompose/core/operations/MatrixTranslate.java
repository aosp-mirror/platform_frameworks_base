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

public class MatrixTranslate extends DrawBase2 {
    public static final Companion COMPANION =
            new Companion(Operations.MATRIX_TRANSLATE) {
                @Override
                public Operation construct(float x1,
                                           float y1
                ) {
                    return new MatrixTranslate(x1, y1);
                }
            };

    public MatrixTranslate(float translateX, float translateY) {
        super(translateX, translateY);
        mName = "MatrixTranslate";
    }

    @Override
    public void paint(PaintContext context) {
        context.matrixTranslate(mV1, mV2);
    }
}
