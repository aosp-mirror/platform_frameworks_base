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

/**
 * Support clip with a rectangle
 */
public class ClipRect extends DrawBase4 {
    public static final Companion COMPANION =
            new Companion(Operations.CLIP_RECT) {
                @Override
                public Operation construct(float x1,
                                           float y1,
                                           float x2,
                                           float y2) {
                    return new ClipRect(x1, y1, x2, y2);
                }
            };

    public ClipRect(
            float left,
            float top,
            float right,
            float bottom) {
        super(left, top, right, bottom);
        mName = "ClipRect";
    }

    @Override
    public void paint(PaintContext context) {
        context.clipRect(mX1, mY1, mX2, mY2);
    }
}
